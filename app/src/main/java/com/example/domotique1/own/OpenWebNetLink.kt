package com.example.domotique1.own

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Liaison TCP vers une passerelle MyHome/OpenWebNet (port 20000), ecrite a la main :
 * aucune dependance externe.
 *
 * Une instance = une session OPEN :
 * - session commandes ([monitor] = false) : trame d'ouverture `*99*0##`
 * - session surveillance ([monitor] = true) : trame d'ouverture `*99*1##`
 *
 * Ce fichier est le SEUL a manipuler les sockets. Les donnees lues sont assemblees puis
 * decoupees en trames completes ([OpenWebNet.FRAME_END] = '##') avant d'etre transmises a
 * [frameListener] : une meme lecture TCP peut contenir plusieurs trames, et une trame peut
 * etre repartie sur deux lectures. Sans ce decoupage exact, les trames se collent et sont
 * mal analysees, ce qui donnait auparavant de fausses "latences de plusieurs minutes".
 *
 * Le module est volontairement "bete" : il se contente d'ecrire, de lire, de separer les
 * trames, et de tout consigner dans [traffic] pour la console de debug.
 */
class OpenWebNetLink(
    private val host: String,
    private val port: Int,
    private val monitor: Boolean,
    /**
     * Duree maximale de silence sur la session avant qu'une trame de maintien
     * ([OpenWebNet.FRAME_ACK], une simple trame d'accuse qui ne commande rien sur le bus)
     * soit envoyee. Le timeout de lecture est fixe a cette meme valeur, ce qui garantit que
     * le keepalive part avant que la passerelle ou le reseau ne ferme la session.
     */
    var keepaliveMs: Long = KEEPALIVE_MS,
    /** journal partage des deux sessions, alimente dans les deux sens */
    private val traffic: TrafficBus = TrafficBus(),
) {
    val name: String = if (monitor) OpenWebNet.SESSION_MON else OpenWebNet.SESSION_CMD
    private val sessionFrame: String = if (monitor) OpenWebNet.MON_SESSION else OpenWebNet.CMD_SESSION

    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    /** derniere trame complete recue, utile pour l'affichage temps reel */
    @Volatile
    var lastFrame: String? = null
        private set

    val connected: Boolean get() = _status.value.connected

    @Volatile
    private var active = false

    @Volatile
    private var frameListener: ((String) -> Unit)? = null

    private var socket: Socket? = null
    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null

    private var lastKeepaliveAt = 0L

    /**
     * Date d'envoi de la derniere trame de maintien restee sans echo. Seule la boucle de
     * lecture ecrit et lit cette valeur, aucun verrou n'est donc necessaire.
     */
    @Volatile
    private var keepaliveSentAt = 0L

    private val consecutiveFailures = AtomicInteger(0)

    /** Etat observable d'une liaison. */
    data class LinkStatus(
        val connected: Boolean = false,
        val attempts: Int = 0,
        val rxFrames: Int = 0,
        val txFrames: Int = 0,
        val keepalives: Int = 0,
        /** echos de trames de maintien recus, avec leur duree d'aller-retour */
        val keepaliveAcks: Int = 0,
        val lastKeepaliveRttMs: Long = -1L,
        val lastKeepaliveAckAt: Long = 0L,
        val reconnects: Int = 0,
        val lastRxAt: Long = 0L,
        val lastTxAt: Long = 0L,
        val lastFrame: String? = null,
        val lastError: String? = null,
    )

    /**
     * Date du dernier envoi d'une trame de maintien qui n'a pas encore recu son echo,
     * ou 0 si aucune n'est en attente. La passerelle nous renvoie fidelement cette trame :
     * [MyHomeGateway] s'en sert pour reconnaitre un echo de keepalive et ne pas le confondre
     * avec l'accuse d'une vraie commande.
     */
    val pendingKeepaliveAt: Long get() = keepaliveSentAt

    /** Oublie le maintien en attente d'echo (une vraie commande vient d'etre prise en compte). */
    fun resetKeepalivePending() {
        keepaliveSentAt = 0L
    }

    /** Comptabilise un echo de trame de maintien : preuve que la session est vivante. */
    fun noteKeepaliveEcho(rttMs: Long) {
        keepaliveSentAt = 0L
        val now = System.currentTimeMillis()
        _status.update {
            it.copy(
                keepaliveAcks = it.keepaliveAcks + 1,
                lastKeepaliveRttMs = rttMs,
                lastKeepaliveAckAt = now,
                connected = true,
                lastError = null,
            )
        }
    }

    /** Resultat brut d'une ecriture dans le socket. */
    enum class SendResult { SENT, NOT_CONNECTED, IO_ERROR }

    /**
     * Ouvre la session et boucle en lecture jusqu'a [stop].
     * La trame d'ouverture est ecrite des la connexion, comme le fait la bibliotheque openwebnet4j.
     */
    fun loop(onFrame: (String) -> Unit) {
        frameListener = onFrame
        active = true
        startLoop()
    }

    fun stop() {
        active = false
        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null
        closeSocket()
        _status.update { it.copy(connected = false) }
    }

    /**
     * Ferme seulement le socket : la boucle de reconnexion prend le relais. A n'utiliser que
     * depuis un thread d'execution ([MyHomeGateway] route cet appel hors du thread principal).
     */
    fun closeSocketForReconnect() {
        _status.update { it.copy(lastError = "reconnexion demandee") }
        closeSocket()
    }

    /** Vrai si le socket est ouvert : appel synchrone, sans acces reseau bloquant. */
    fun isReady(): Boolean {
        val sk = socket ?: return false
        return !sk.isClosed && sk.isConnected
    }

    /**
     * Ecrit une trame complete (doit se terminer par '##') sans attendre de reponse.
     * [kind] / [note] ne servent qu'a la console de debug.
     */
    fun send(frame: String, kind: String = TrafficEntry.COMMAND, note: String = ""): SendResult {
        val sk = socket
        if (sk == null || sk.isClosed || !sk.isConnected) {
            traffic.record(name, outgoing = true, kind = TrafficEntry.FAILED, raw = frame, note = "session fermee")
            return SendResult.NOT_CONNECTED
        }
        if (kind == TrafficEntry.COMMAND) {
            // une vraie commande part : l'eventuel maintien en attente d'echo n'est plus le
            // candidat a rapprocher, sinon son echo serait affiche comme la reponse a la commande
            keepaliveSentAt = 0L
        }
        return try {
            val out = sk.getOutputStream()
            out.write(frame.toByteArray(Charsets.US_ASCII))
            out.flush()
            val now = System.currentTimeMillis()
            _status.update { it.copy(txFrames = it.txFrames + 1, lastTxAt = now) }
            traffic.record(name, outgoing = true, kind = kind, raw = frame, note = note)
            Log.i(TAG, "[$name] TX $frame")
            SendResult.SENT
        } catch (e: IOException) {
            traffic.record(
                name,
                outgoing = true,
                kind = TrafficEntry.FAILED,
                raw = frame,
                note = "ecriture refusee : ${e.message ?: e.javaClass.simpleName}",
            )
            Log.e(TAG, "[$name] TX ECHOUE $frame : ${e.message}")
            _status.update { it.copy(lastError = e.message ?: "erreur ecriture") }
            closeSocketForReconnect()
            SendResult.IO_ERROR
        }
    }

    // ------------------------------------------------------------------ boucle

    private fun startLoop() {
        if (!active) return
        loopJob?.cancel()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        _status.update { it.copy(attempts = it.attempts + 1) }
        loopJob = sc.launch {
            while (active) {
                connectAndRead()
                if (!active) break
                val failures = consecutiveFailures.get()
                val wait = backoffMs(failures)
                Log.i(TAG, "[$name] nouvelle connexion dans ${wait} ms (echecs : $failures)")
                delay(wait)
            }
        }
    }

    private fun connectAndRead() {
        _status.update { it.copy(lastError = null) }
        var input: InputStream? = null
        try {
            val sk = Socket()
            sk.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sk.keepAlive = true
            // timeout de lecture = intervalle de keepalive : a chaque expiration on relance la session
            sk.soTimeout = keepaliveMs.toInt()
            socket = sk

            input = sk.getInputStream()
            keepaliveSentAt = 0L
            writeSessionFrame(sk)

            consecutiveFailures.set(0)
            keepaliveSentAt = 0L
            lastKeepaliveAt = System.currentTimeMillis()
            _status.update { it.copy(connected = true, lastError = null) }
            Log.i(TAG, "[$name] connecte a $host:$port (session ${if (monitor) "surveillance" else "commandes"})")

            readFrames(input)

            consecutiveFailures.incrementAndGet()
        } catch (e: IOException) {
            consecutiveFailures.incrementAndGet()
            val msg = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "[$name] connexion/lecture impossible : $msg")
            _status.update { it.copy(connected = false, lastError = msg) }
            traffic.record(name, outgoing = false, kind = TrafficEntry.FAILED, raw = "", note = "connexion : $msg")
        } finally {
            try {
                input?.close()
            } catch (_: IOException) {
            }
            closeSocket()
            if (active) {
                _status.update { it.copy(connected = false, reconnects = it.reconnects + 1) }
                Log.i(TAG, "[$name] session fermee, reconnexion en cours")
            }
        }
    }

    /**
     * Lit et traite les trames jusqu'a la fermeture de la session.
     *
     * Le tampon [pending] garde ce qui a ete lu sans find de fin '##' : la trame est traitee
     * des que sa fin arrive, ce qui est important sur les frames longues (rapports d'etat
     * avances) et pendant les rafales de la passerelle.
     */
    private fun readFrames(input: InputStream) {
        val pending = StringBuilder()
        val buffer = ByteArray(4096)
        while (active) {
            val count: Int
            try {
                count = input.read(buffer)
            } catch (timeout: SocketTimeoutException) {
                // rien depuis keepaliveMs : on ecrit une trame de maintien
                keepaliveIfNeeded()
                continue
            } catch (e: IOException) {
                if (active) {
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "[$name] lecture interrompue : $msg")
                    _status.update { it.copy(lastError = msg) }
                }
                return
            }

            if (count < 0) {
                if (active) {
                    Log.i(TAG, "[$name] le flux a ete ferme par la passerelle")
                    traffic.record(name, outgoing = false, kind = TrafficEntry.FAILED, raw = "", note = "flux ferme")
                    _status.update { it.copy(lastError = "flux ferme par la passerelle") }
                }
                return
            }
            if (count == 0) continue

            pending.append(String(buffer, 0, count, Charsets.US_ASCII))
            extractFrames(pending)
        }
    }

    /**
     * Decoupe le tampon en trames completes et les transmet a [frameListener].
     * Une lecture peut contenir Zero, une ou plusieurs trames : le separateur de fin de
     * trame OPEN est '##' ([OpenWebNet.FRAME_END]), toujours en fin de trame.
     */
    private fun extractFrames(pending: StringBuilder) {
        val endLen = OpenWebNet.FRAME_END.length
        while (true) {
            val end = pending.indexOf(OpenWebNet.FRAME_END)
            if (end < 0) break
            var raw = pending.substring(0, end + endLen)
            pending.delete(0, end + endLen)

            // la passerelle envoie parfois du bruit (sauts de ligne, octets de service) : on l'ignore
            val start = raw.indexOf(OpenWebNet.FRAME_START)
            if (start < 0) {
                Log.w(TAG, "[$name] donnees ignorees : $raw")
                continue
            }
            if (start > 0) {
                Log.w(TAG, "[$name] bruit ignore avant la trame : ${raw.substring(0, start)}")
                raw = raw.substring(start)
            }
            handleFrame(raw)
        }
        if (pending.length > MAX_PENDING_CHARS) {
            Log.w(TAG, "[$name] tampon trop long sans find de fin, remise a zero : $pending")
            pending.setLength(0)
        }
    }

    /** Consigne et transmet une trame complete. */
    private fun handleFrame(raw: String) {
        val now = System.currentTimeMillis()
        lastFrame = raw
        _status.update {
            it.copy(rxFrames = it.rxFrames + 1, lastFrame = raw, lastRxAt = now, lastError = null)
        }
        traffic.record(name, outgoing = false, kind = TrafficEntry.INCOMING, raw = raw)
        Log.i(TAG, "[$name] RX $raw")
        frameListener?.invoke(raw)
    }

    /** Trame d'ouverture de session OPEN (`*99*0##` ou `*99*1##`). */
    private fun writeSessionFrame(sk: Socket) {
        val out = sk.getOutputStream()
        out.write(sessionFrame.toByteArray(Charsets.US_ASCII))
        out.flush()
        val now = System.currentTimeMillis()
        _status.update { it.copy(txFrames = it.txFrames + 1, lastTxAt = now) }
        traffic.record(
            name,
            outgoing = true,
            kind = TrafficEntry.OPEN,
            raw = sessionFrame,
            note = if (monitor) "ouverture session surveillance" else "ouverture session commandes",
        )
        Log.i(TAG, "[$name] ouverture de session : $sessionFrame")
    }

    /**
     * Envoye une trame de maintien si le silence depasse [keepaliveMs].
     *
     * `*#*1##` est la trame d'accuse du protocole : elle ne contient aucune adresse de volet
     * et ne deplace donc rien sur le bus ; la passerelle repond par le meme accuse, ce qui
     * prouve que la session est vivante.
     */
    private fun keepaliveIfNeeded() {
        val sk = socket ?: return
        if (sk.isClosed) return
        val now = System.currentTimeMillis()
        if (now - lastKeepaliveAt < keepaliveMs) return
        lastKeepaliveAt = now
        try {
            val out = sk.getOutputStream()
            out.write(OpenWebNet.FRAME_ACK.toByteArray(Charsets.US_ASCII))
            out.flush()
            // a partir d'ici, tout accuse recu sur cette session est considere comme son echo
            keepaliveSentAt = now
            _status.update {
                it.copy(keepalives = it.keepalives + 1, lastTxAt = now, connected = true, lastError = null)
            }
            traffic.record(
                name,
                outgoing = true,
                kind = TrafficEntry.KEEPALIVE,
                raw = OpenWebNet.FRAME_ACK,
                note = "maintien session (aucun volet commande)",
            )
            Log.d(TAG, "[$name] keepalive ${OpenWebNet.FRAME_ACK}")
        } catch (e: IOException) {
            traffic.record(
                name,
                outgoing = true,
                kind = TrafficEntry.FAILED,
                raw = OpenWebNet.FRAME_ACK,
                note = "keepalive : ${e.message ?: e.javaClass.simpleName}",
            )
            Log.w(TAG, "[$name] keepalive echoue : ${e.message}")
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun backoffMs(attempt: Int): Long = when {
        attempt <= 1 -> 1_500L
        attempt >= MAX_ATTEMPTS -> MAX_BACKOFF_MS
        else -> BACKOFF_STEP_MS * attempt
    }

    private companion object {
        const val TAG = "OpenWebNetLink"

        /** timeout de connexion TCP */
        const val CONNECT_TIMEOUT_MS = 5_000

        /** intervalle de keepalive par defaut, si le createur ne le precise pas */
        const val KEEPALIVE_MS = 20_000L

        /** garde-fou : au dela, le tampon est vide (trame jamais terminee) */
        const val MAX_PENDING_CHARS = 4_000

        /** backoff de reconnexion : 1.5 s, 3 s, 4.5 s, ... plafonne a 30 s */
        const val BACKOFF_STEP_MS = 1_500L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_ATTEMPTS = 20
    }

    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        while (true) {
            val old = value
            if (compareAndSet(old, transform(old))) return
        }
    }
}
