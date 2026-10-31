package com.example.domotique1.own

import android.util.Log
import java.util.ArrayDeque
import com.example.domotique1.own.OpenWebNetLink.SendResult
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Etat global des deux sessions (CMD + MON) de la passerelle. */
data class GatewayStatus(
    val host: String = "",
    val port: Int = 0,
    val cmdConnected: Boolean = false,
    val monConnected: Boolean = false,
    val detail: String = "non connecte",
    /** diagnostics : trafic observe avec la passerelle */
    val cmdTx: Int = 0,
    val cmdRx: Int = 0,
    val monRx: Int = 0,
    val reconnects: Int = 0,
    val keepalives: Int = 0,
    /** derniere trame recue, toutes sessions confondues */
    val lastFrame: String = "",
    val lastRxAt: Long = 0,
    /** session ou cette derniere trame a ete recue : "CMD" ou "MON" */
    val lastRxSession: String = "",
    /** derniere commande ecrite sur le port 20000 (session commandes) */
    val lastSentFrame: String = "",
    val lastSentAt: Long = 0,
    /** libelle lisible de cette commande (piece + mouvement), pose par l'application */
    val lastSentLabel: String = "",
    /** reponse recue en face de cette commande (accuse, rapport, echo de keepalive) */
    val lastAnswerFrame: String = "",
    val lastAnswerAt: Long = 0,
    val lastAnswerIsNack: Boolean = false,
    /**
     * vrai si la "reponse" affichee en face de la derniere commande n'est pas une vraie
     * reponse : echo de notre propre trame de maintien, ou trame emise par la passerelle
     * d'elle-meme. L'affichage doit alors dire "pas de reponse", jamais une latence farfelue.
     */
    val lastAnswerIsNotReply: Boolean = false,
    /**
     * latence mesuree entre l'envoi de la trame affichee et la reponse affichee en face,
     * uniquement quand les deux ont ete reellement rapproches. -1 sinon : jamais calcule,
     * echo de maintien ou trame spontanee.
     */
    val lastAnswerLatencyMs: Long = -1,
    /** latence du dernier accuse recu, en ms ; -1 = jamais mesure */
    val lastAckLatencyMs: Long = -1,
    /** echos des trames de maintien (sante des sessions, sans lien avec les volets) */
    val keepaliveAcks: Int = 0,
    val lastKeepaliveRttMs: Long = -1,
    val lastKeepaliveAckAt: Long = 0,
    /** compteurs de la console de trafic (toutes sessions) */
    val trafficOut: Int = 0,
    val trafficIn: Int = 0,
    val trafficErrors: Int = 0,
) {
    val connected: Boolean get() = cmdConnected || monConnected

    /** vrai si l'accuse est arrive plus de 5 s apres l'envoi (anormal sur ce MyHome) */
    val ackIsDelayed: Boolean get() = lastAckLatencyMs > 5_000

    /**
     * latence entre la derniere commande envoyee et la reponse qui lui a reellement ete
     * associee ; -1 si aucune reponse exploitable n'a ete vue.
     *
     * Cette valeur est mesuree au moment du rapprochement, pas deduite de l'horodatage de
     * la derniere trame envoyee : lors d'une rafale de demandes, la derniere trame envoyee
     * n'est pas celle a laquelle la reponse affichee correspond.
     */
    val answerLatencyMs: Long get() = lastAnswerLatencyMs
}

/**
 * Connexion complete a une passerelle MyHome/OpenWebNet : une session CMD (commandes)
 * et une session MON (evenements du bus), comme le fait openwebnet4j.
 *
 * Comportement constate sur 192.168.1.143 (MyHome BTicino, firmware 03002001C064080EE) :
 * la passerelle repond en moins de 200 ms (mesures du 29/07, see the probe transcript kept in
 * the temp folder): `*#2*51##` -> `*2*0*51##` about 100 ms later, then the ACK `*#*1##`
 * about 100 ms after that. For a read request the DATA answer therefore arrives BEFORE the
 * ACK, so [send] never waits for an answer: it returns as soon as the frame is handed to the
 * [writer] thread. When an ACK does arrive it is matched with the last frame sent and its
 * latency is published in [notices] and in [GatewayStatus.answerLatencyMs] as a diagnostic.
 *
 * Les trames recues (CMD + MON, doublons filtres) sont parsees et publiees dans [frames].
 * Chaque trame qui traverse le port 20000, dans un sens ou dans l'autre, est aussi ecrite
 * dans [traffic] (console de debug).
 */
class MyHomeGateway {

    private var scope: CoroutineScope? = null
    private var cmdLink: OpenWebNetLink? = null
    private var monLink: OpenWebNetLink? = null
    private var jobs: List<Job> = emptyList()

    /**
     * File mono-thread ou sont ecrites toutes les trames. Les boutons appellent [send] depuis
     * le thread principal d'Android, qui a le droit d'ecrire nulle part sur le reseau : sans
     * cette file l'application ferme avec NetworkOnMainThreadException des qu'on touche un volet.
     */
    private val writer: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "own-writer").apply { isDaemon = true }
    }

    /** journal partage des deux sessions : ce qui est ecrit et ce qui est lu sur le port 20000 */
    val traffic = TrafficBus()
    val trafficEntries: StateFlow<List<TrafficEntry>> get() = traffic.entries
    val trafficTotals: StateFlow<TrafficTotals> get() = traffic.totals

    private val _frames = MutableSharedFlow<OwnFrame>(extraBufferCapacity = 512)
    val frames: SharedFlow<OwnFrame> = _frames.asSharedFlow()

    /** Informations de diagnostic (latence des accuses, etats des liaisons). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    /** Commandes envoyees et non encore accusees (file FIFO, indicative seulement). */
    private val sentQueue = ArrayDeque<Pair<String, Long>>()

    /** Les reports arrivent parfois sur CMD ET MON (ou sont reemis) : on filtre les doublons. */
    private val recentFrames = HashMap<String, Long>()

    fun connect(host: String, port: Int) {
        disconnect()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        _status.value = GatewayStatus(host, port, detail = "connexion a $host:$port...")

        val cmd = OpenWebNetLink(host, port, monitor = false, traffic = traffic)
        val mon = OpenWebNetLink(host, port, monitor = true, traffic = traffic)
        cmdLink = cmd
        monLink = mon

        // les trames sont livrees par callback synchrone (pas de collect) : aucune ne se perd
        jobs = listOf(
            sc.launch { cmd.loop { raw -> onCmdFrame(raw) } },
            sc.launch { mon.loop { raw -> onMonFrame(raw) } },
            sc.launch { cmd.status.collect { publish() } },
            sc.launch { mon.status.collect { publish() } },
            sc.launch { traffic.totals.collect { publish() } },
        )
    }

    fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val cmd = cmdLink
        val mon = monLink
        cmdLink = null
        monLink = null
        scope = null
        // fermer un socket est une entree reseau : on le fait hors du thread principal
        writer.execute {
            cmd?.stop()
            mon?.stop()
        }
        synchronized(sentQueue) { sentQueue.clear() }
        _status.value = GatewayStatus(detail = "arrete")
    }

    /**
     * Envoie une trame sur la session CMD sans attendre d'accuse.
     *
     * L'ecriture reelle est placee dans une file mono-thread ([writer]) : les boutons de
     * l'interface appellent cette methode depuis le thread principal, et Android interdit
     * d'ecrire dans un socket depuis ce thread (NetworkOnMainThreadException). La file
     * preserve l'ordre des trames, donc un arret reste toujours envoye apres sa montee.
     *
     * [queued] : true si la trame doit etre rapprochee d'un accuse eventuel (ordre de volet).
     * [label] : libelle affiche dans l'application a cote de la trame.
     */
    fun send(frame: String, queued: Boolean = true, label: String = ""): SendResult {
        val link = cmdLink ?: return SendResult.NOT_CONNECTED
        if (!link.isReady()) {
            traffic.record(
                session = link.name,
                outgoing = true,
                kind = TrafficEntry.FAILED,
                raw = frame,
                note = "session fermee",
            )
            return SendResult.NOT_CONNECTED
        }
        val now = System.currentTimeMillis()
        if (queued) {
            synchronized(sentQueue) {
                sentQueue.addLast(frame to now)
                while (sentQueue.size > 40) sentQueue.removeFirst()
            }
            // une nouvelle demande part : on efface l'affichage de la reponse precedente
            // (souvent un echo du maintien) pour que la bande affiche "en attente" jusqu'a
            // la vraie reponse, qui sera cette fois reellement associee a cette trame.
            // keepaliveSentAt = 0 : l'echo du maintien en cours n'est plus candidat, sinon
            // il arriverait plus bas en face de la commande avec une latence fausse.
            cmdLink?.resetKeepalivePending()
            updateStatus {
                it.copy(
                    lastSentFrame = frame,
                    lastSentAt = now,
                    lastSentLabel = label,
                    lastAnswerFrame = "",
                    lastAnswerAt = 0,
                    lastAnswerIsNack = false,
                    lastAnswerIsNotReply = false,
                    lastAnswerLatencyMs = -1,
                )
            }
        } else {
            updateStatus { it.copy(lastSentFrame = frame, lastSentAt = now, lastSentLabel = label) }
        }
        writer.execute {
            if (link.send(frame, kind = TrafficEntry.COMMAND, note = label) != SendResult.SENT) {
                _notices.tryEmit("ecriture impossible sur la session commandes : $frame")
                updateStatus { it.copy(detail = "ecriture $frame refusee") }
            }
        }
        return SendResult.SENT
    }

    /** Envoi force sur la session MON (ne sert qu'a maintenir la session / tester). */
    fun sendOnMonitor(frame: String): SendResult {
        val link = monLink ?: return SendResult.NOT_CONNECTED
        if (!link.isReady()) return SendResult.NOT_CONNECTED
        writer.execute { link.send(frame) }
        return SendResult.SENT
    }

    /** Vrai si les sessions ont deja ete creees (connect() a ete appelee au moins une fois). */
    fun hasLinks(): Boolean = cmdLink != null || monLink != null

    /** Relance les connexions qui semblent bloquees. */
    fun reconnectLinks() {
        val cmd = cmdLink
        val mon = monLink
        writer.execute {
            cmd?.closeSocketForReconnect()
            mon?.closeSocketForReconnect()
        }
    }

    /** Remet la console de trafic a zero. */
    fun clearTraffic() = traffic.clear()

    // ------------------------------------------------------------------ internals

    private fun onCmdFrame(raw: String) {
        val frame = OwnFrame.parse(raw)
        if (frame.isAck || frame.isNack) {
            matchAck(raw, frame)
            return
        }
        // trame utile recue sur la session commandes. On ne l'affiche en face de notre derniere
        // demande que si elle lui correspond vraiment (meme WHO et meme WHERE que la demande en
        // tete de file) : sinon c'est la passerelle qui emet d'elle-meme, et l'afficher comme
        // notre "reponse" afficherait une latence sans aucun rapport.
        val now = System.currentTimeMillis()
        val asked: Pair<String, Long>? = synchronized(sentQueue) {
            val head = sentQueue.peekFirst()
            if (head != null && answersRequest(head.first, frame)) head else null
        }
        updateStatus {
            it.copy(
                lastAnswerFrame = raw,
                lastAnswerAt = now,
                lastAnswerIsNack = false,
                lastAnswerIsNotReply = asked == null,
                lastAnswerLatencyMs = asked?.let { head -> now - head.second } ?: -1,
            )
        }
        when (asked) {
            null -> Log.d(TAG, "trame $raw recue sans demande correspondante : pas une reponse")
            else -> Log.d(TAG, "trame $raw = reponse a ${asked.first} recue apres ${formatDuration(now - asked.second)}")
        }
        publishFrame(frame.copy(session = OpenWebNet.SESSION_CMD), raw)
    }

    /**
     * Vrai si [frame] recue peut etre la reponse a [request] envoyee par nous : une demande
     * `*#WHO*WHERE##` revient avec le meme WHO et le meme WHERE (`*#2*51##` -> `*2*0*51##`),
     * et la passerelle nous renvoie aussi nos propres ordres a l'identique.
     */
    private fun answersRequest(request: String, frame: OwnFrame): Boolean {
        val asked = OwnFrame.parse(request)
        return asked.who != null && frame.who == asked.who && frame.where == asked.where
    }

    private fun onMonFrame(raw: String) {
        // un accuse recu sur la session surveillance est le renvoi de notre trame de maintien :
        // il confirme que la session MON tient, mais ne dit rien d'un volet
        if (raw == OpenWebNet.FRAME_ACK) {
            val link = monLink ?: return
            val at = link.pendingKeepaliveAt
            if (at > 0) link.noteKeepaliveEcho(System.currentTimeMillis() - at)
        }
        publishFrame(OwnFrame.parse(raw).copy(session = OpenWebNet.SESSION_MON), raw)
    }

    private fun publishFrame(frame: OwnFrame, raw: String) {
        if (isDuplicate(raw)) return
        _frames.tryEmit(frame)
    }

    /**
     * Rapproche un accuse de la commande restee en attente. Sur cette passerelle l'accuse
     * revient en general en moins de 300 ms : on le consigne, sans jamais bloquer l'envoi
     * suivant en attendant.
     *
     * Deux cas particuliers a ne pas melanger, sous peine d'afficher des latences absurdes :
     * - echo de notre trame de maintien : quand aucune commande n'est en attente, un accuse
     *   recu peu apres un keepalive est le renvoi de celui-ci. Il prouve que la session est
     *   vivante, il ne dit rien d'un volet ;
     * - commande oubliee : une commande dont l'accuse n'est jamais revenu ne doit plus etre
     *   associee a un accuse arrive des minutes plus tard, on l'expire.
     */
    private fun matchAck(raw: String, frame: OwnFrame) {
        val now = System.currentTimeMillis()
        val pending = synchronized(sentQueue) {
            while (sentQueue.isNotEmpty() && now - sentQueue.peekFirst().second > ACK_LIFETIME_MS) {
                val (lost, lostAt) = sentQueue.pollFirst()
                Log.w(TAG, "aucun accuse pour $lost (envoyee depuis ${formatDuration(now - lostAt)}) : demande expiree")
            }
            sentQueue.pollFirst()
        }

        if (pending == null) {
            val keepaliveAt = cmdLink?.pendingKeepaliveAt ?: 0L
            if (keepaliveAt > 0 && now - keepaliveAt < KEEPALIVE_ECHO_MS) {
                val rtt = now - keepaliveAt
                cmdLink?.noteKeepaliveEcho(rtt)
                updateStatus { st ->
                    st.copy(
                        lastAnswerFrame = raw,
                        lastAnswerAt = now,
                        lastAnswerIsNack = false,
                        lastAnswerIsNotReply = true,
                        lastAnswerLatencyMs = -1,
                    )
                }
                Log.d(TAG, "echo du maintien recu apres ${formatDuration(rtt)} : session vivante")
            } else {
                updateStatus {
                    it.copy(
                        lastAnswerFrame = raw,
                        lastAnswerAt = now,
                        lastAnswerIsNack = frame.isNack,
                        lastAnswerIsNotReply = true,
                        lastAnswerLatencyMs = -1,
                    )
                }
                Log.d(TAG, "accus $raw recu sans commande en attente")
            }
            return
        }

        val (sentFrame, sentAt) = pending
        val latency = now - sentAt
        updateStatus {
            it.copy(
                lastAnswerFrame = raw,
                lastAnswerAt = now,
                lastAnswerIsNack = frame.isNack,
                lastAnswerIsNotReply = false,
                lastAnswerLatencyMs = latency,
                lastAckLatencyMs = latency,
            )
        }
        val kind = if (frame.isAck) "acceptee" else "REFUSEE"
        val text = "accus recu apres ${formatDuration(latency)} : $sentFrame -> $kind"
        Log.i(TAG, text)
        _notices.tryEmit(text)
    }

    /** Vrai si la meme trame brute a deja ete vue il y a moins de [DEDUPE_MS] (CMD + MON). */
    private fun isDuplicate(raw: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recentFrames) {
            val prev = recentFrames[raw]
            recentFrames[raw] = now
            if (recentFrames.size > 200) {
                val iter = recentFrames.entries.iterator()
                while (iter.hasNext()) {
                    if (now - iter.next().value > DEDUPE_MS) iter.remove()
                }
            }
            return prev != null && now - prev < DEDUPE_MS
        }
    }

    /** Recalcule [status] a partir des deux liaisons et du journal de trafic. */
    private fun publish() {
        val cmd = cmdLink?.status?.value
        val mon = monLink?.status?.value
        val tot = traffic.totals.value
        val cmdRxAt = cmd?.lastRxAt ?: 0L
        val monRxAt = mon?.lastRxAt ?: 0L
        val detail = when {
            cmd?.connected == true && mon?.connected == true -> "connecte (CMD + MON)"
            cmd?.connected == true -> "connecte (CMD seul)"
            mon?.connected == true -> "connecte (MON seul)"
            else -> {
                val errors = listOfNotNull(cmd?.lastError, mon?.lastError)
                    .filter { it.isNotBlank() }.distinct()
                if (errors.isEmpty()) "connexion en cours..." else errors.joinToString(" / ")
            }
        }
        updateStatus { st ->
            st.copy(
                cmdConnected = cmd?.connected == true,
                monConnected = mon?.connected == true,
                detail = detail,
                cmdTx = cmd?.txFrames ?: 0,
                cmdRx = cmd?.rxFrames ?: 0,
                monRx = mon?.rxFrames ?: 0,
                reconnects = (cmd?.reconnects ?: 0) + (mon?.reconnects ?: 0),
                keepalives = (cmd?.keepalives ?: 0) + (mon?.keepalives ?: 0),
                keepaliveAcks = (cmd?.keepaliveAcks ?: 0) + (mon?.keepaliveAcks ?: 0),
                lastKeepaliveRttMs = latest(cmd?.lastKeepaliveAckAt ?: 0L, mon?.lastKeepaliveAckAt ?: 0L,
                    cmd?.lastKeepaliveRttMs ?: -1L, mon?.lastKeepaliveRttMs ?: -1L),
                lastKeepaliveAckAt = maxOf(cmd?.lastKeepaliveAckAt ?: 0L, mon?.lastKeepaliveAckAt ?: 0L),
                lastFrame = if (monRxAt >= cmdRxAt) mon?.lastFrame ?: st.lastFrame
                else cmd?.lastFrame ?: st.lastFrame,
                lastRxAt = maxOf(cmdRxAt, monRxAt),
                lastRxSession = if (monRxAt >= cmdRxAt) OpenWebNet.SESSION_MON else OpenWebNet.SESSION_CMD,
                trafficOut = tot.sent,
                trafficIn = tot.received,
                trafficErrors = tot.errors,
            )
        }
    }

    /**
     * Garde la mesure de duree associee a l'horodatage le plus recent (-1 = jamais mesure).
     * Les deux sessions envoient chacune leur trame de maintien : la derniere meslee gagne.
     */
    private fun latest(atCmd: Long, atMon: Long, rttCmd: Long, rttMon: Long): Long =
        if (atMon > atCmd) rttMon else rttCmd

    /** Mise a jour atomique de [status] depuis un thread de lecture. */
    private fun updateStatus(transform: (GatewayStatus) -> GatewayStatus) {
        while (true) {
            val old = _status.value
            if (_status.compareAndSet(old, transform(old))) return
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 2_000 -> "$ms ms"
        ms < 120_000 -> "${ms / 1_000} s"
        else -> "${ms / 60_000} min ${(ms % 60_000) / 1_000} s"
    }

    companion object {
        private const val TAG = "OWN-gateway"

        /**
         * Fenetre de deduplication : la passerelle reemet parfois la meme trame dans la meme
         * rafale, et une trame utile peut etre vue sur CMD et MON. Les appuis sur un
         * interrupteur mural arrivent a plusieurs secondes d'intervalle, la fenetre reste courte.
         */
        private const val DEDUPE_MS = 900L

        /**
         * Duree de vie d'une commande en attente d'accuse : au dela, on oublie la demande.
         * Sans cette expirement, un echo de trame de maintien arrive des minutes plus tard
         * serait pris pour l'accuse de la commande et afficherait une latence absurde.
         */
        private const val ACK_LIFETIME_MS = 10_000L

        /** fenetre pendant laquelle un accuse recu est considere comme l'echo du keepalive. */
        private const val KEEPALIVE_ECHO_MS = 15_000L
    }
}
