package com.example.domotique1.data

import android.content.Context
import android.util.Log
import com.example.domotique1.own.GatewayStatus
import com.example.domotique1.own.MyHomeGateway
import com.example.domotique1.own.OpenWebNet
import com.example.domotique1.own.OwnFrame
import com.example.domotique1.own.OwnMovement
import com.example.domotique1.own.OpenWebNetLink.SendResult
import com.example.domotique1.own.TrafficEntry
import com.example.domotique1.own.TrafficTotals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Une ligne du journal des trames. */
data class FrameLogEntry(
    val at: Long,
    val time: String,
    val raw: String,
    /** sens : "CMD >" (envoye), "CMD <" (reponse de la passerelle), "MON <" (trame du bus), "ESSAI" */
    val dir: String,
    /** ce que la trame veut dire pour l'utilisateur */
    val text: String,
)

/**
 * Une adresse de volet vue sur le bus depuis le demarrage de l'application.
 *
 * Les appuis sur les interrupteurs muraux et les rapports d'etat de la passerelle font
 * apparaitre les vraies adresses "where" : c'est comme cela qu'on les trouve, sans
 * envoyer de commande de test.
 */
data class ObservedWhere(
    val where: Int,
    /** nombre de trames vues pour cette adresse */
    val seen: Int,
    val lastRaw: String,
    val lastAt: Long,
) {
    /** deja affectee a un volet ? (verifie par l'ecran Reglages) */
    fun ageMs(): Long = System.currentTimeMillis() - lastAt
}

/** Etat de la connexion vu par l'interface (la liaison tente sans cesse de se reconnecter). */
data class UiStatus(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val detail: String = "arrete",
    /** etat detaille des deux sessions, pour l'ecran Reglages */
    val gateway: GatewayStatus = GatewayStatus(),
)

/**
 * Logique metier de l'application :
 *  - connexion a la passerelle (session commandes CMD + session surveillance MON)
 *  - envoi des ordres MONTER / DESCENDRE / STOP des differents volets
 *  - arret automatique en fin de course, avec duree de course reglable par volet
 *
 * Les vues observent [volets], [states] et [status] : l'etat est partage entre l'ecran
 * principal et les reglages, et survit aux rotations d'ecran.
 *
 * Constat sur la passerelle 192.168.1.143 : elle repond en moins d'une seconde, mais ses
 * rapports d'etat arrivent par la session surveillance. L'interface est quand meme mise a
 * jour des que la trame est partie, sans attendre l'accuse.
 */
class VoletsRepository private constructor(private val appContext: Context) {

    private val store = ShutterStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** la passerelle (sessions + journal brut), exposee pour les reglages */
    val gateway = MyHomeGateway()

    // ------------------------------------------------------------ etat visible

    private val _volets = MutableStateFlow(store.load())
    val volets: StateFlow<List<Shutter>> = _volets.asStateFlow()

    /** etat "en direct" de chaque volet (mouvement + position estimee) */
    private val _states = MutableStateFlow(emptyMap<String, ShutterState>())
    val states: StateFlow<Map<String, ShutterState>> = _states.asStateFlow()

    private val _status = MutableStateFlow(UiStatus())
    val status: StateFlow<UiStatus> = _status.asStateFlow()

    /** volets pour lesquels une commande vient de partir (petit sablier sur les cartes) */
    private val _busy = MutableStateFlow(emptySet<String>())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** adresses "where" vues sur le bus (les plus recentes d'abord) */
    private val _observed = MutableStateFlow(emptyList<ObservedWhere>())
    val observed: StateFlow<List<ObservedWhere>> = _observed.asStateFlow()

    /** journal des trames, les plus recentes en premier */
    private val _log = MutableStateFlow(emptyList<FrameLogEntry>())
    val log: StateFlow<List<FrameLogEntry>> = _log.asStateFlow()

    /** messages de diagnostic (latence des accuses, reconnexions, commandes...) */
    private val _notices = MutableStateFlow(emptyList<String>())
    val notices: StateFlow<List<String>> = _notices.asStateFlow()

    // -------------------------------------------------------------- interne

    /** arrets automatiques programmes (un par volet) */
    private val timers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** dernier mouvement envoye par l'appli, par volet (sert a ignorer les echos) */
    private val localOrders = java.util.concurrent.ConcurrentHashMap<String, OwnMovement>()

    /**
     * Adresses pour lesquelles nous attendons un rapport d'etat (WHERE -> horodatage de la
     * demande). La passerelle repond a `*#2*W##` par `*2*<etat>*W##`, exactement la meme
     * trame qu'un mouvement vu sur le bus : ce lien permet de la lire comme une reponse et
     * non comme un appui sur un interrupteur mural.
     */
    private val statusRequests = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** adresses ou la passerelle a repondu NACK, pour le diagnostic */
    private var rejected = 0

    init {
        scope.launch { gateway.frames.collect { frame -> onFrame(frame) } }
        scope.launch { gateway.notices.collect { text -> pushNotice(text) } }
        scope.launch {
            gateway.status.collect { gw ->
                _status.update {
                    UiStatus(
                        connecting = !gw.connected && gw.detail != "arrete",
                        connected = gw.connected,
                        detail = gw.detail + connectHint(gw),
                        gateway = gw,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- connexion

    /** (re)demarre les deux sessions avec la configuration enregistree. */
    fun connect() {
        val s = store.settings()
        _status.update { it.copy(connecting = true, detail = "connexion a ${s.host}:${s.port}...") }
        gateway.connect(s.host, s.port)
    }

    /** ferme les sessions et annule les arrets automatiques en attente. */
    fun disconnect() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        gateway.disconnect()
        _status.update { UiStatus(connecting = false, connected = false, detail = "arrete") }
    }

    /** relance les deux sessions sans changer la configuration (passerelle muette). */
    fun reconnect() {
        if (gateway.hasLinks()) gateway.reconnectLinks() else connect()
        pushNotice("reconnexion demandee (sessions commandes + surveillance)")
    }

    /** enregistre les reglages puis reconnecte avec ces reglages. */
    fun applySettingsAndConnect(settings: GatewaySettings) {
        store.saveSettings(settings)
        disconnect()
        connect()
    }

    // -------------------------------------------------------------- commandes

    /** MONTER (le volet monte jusqu'a l'ordre STOP ou jusqu'a la fin de course). */
    fun moveUp(id: String) = sendMovement(id, OwnMovement.UP)

    /** DESCENDRE. */
    fun moveDown(id: String) = sendMovement(id, OwnMovement.DOWN)

    /** STOP : interrompt aussi l'arret automatique programme. */
    fun stop(id: String) {
        timers.remove(id)?.cancel()
        sendMovement(id, OwnMovement.STOP)
    }

    /**
     * Envoie un ordre de mouvement a un volet.
     *
     * L'interface est mise a jour des que la trame est partie, sans attendre l'accuse.
     * L'arret automatique est programme pour les mouvements MONTER / DESCENDRE.
     */
    fun sendMovement(id: String, action: OwnMovement) {
        val shutter = find(id) ?: return
        if (action == OwnMovement.STOP) timers.remove(id)?.cancel()
        if (!shutter.configured) {
            pushNotice("${shutter.name} : adresse non definie, rien n'a ete envoye")
            return
        }
        val frame = frameFor(action, shutter.where)
        if (store.settings().dryRun) {
            logFrame(frame, "ESSAI", "${movementLabel(action)} -> ${shutter.name} (mode essai)")
            pushNotice("mode essai : ${movementLabel(action)} -> ${shutter.name} non envoye")
            return
        }
        when (gateway.send(frame, label = "${movementLabel(action)} -> ${shutter.name}")) {
            SendResult.SENT -> {
                logFrame(frame, "CMD >", "${movementLabel(action)} -> ${shutter.name} [WHERE ${shutter.where}]")
                recordLocalOrder(id, action)
                markBusy(id)
                val now = System.currentTimeMillis()
                updateState(id) { prev ->
                    // Un STOP ne dit rien de la position : on garde ce qu'on savait (eventuellement
                    // "inconnue"), et on n'estime que si une position etait deja connue.
                    val pos = when {
                        action == OwnMovement.STOP -> prev.positionPercent
                        prev.hasPosition -> estimatedAfter(shutter, prev, action)
                        else -> prev.positionPercent
                    }
                    prev.copy(action = action, positionPercent = pos, lastChangeAt = now)
                }
                if (action == OwnMovement.UP || action == OwnMovement.DOWN) startAutoStop(id)
            }

            SendResult.NOT_CONNECTED -> {
                logFrame(frame, "CMD >", "ECHEC envoi vers ${shutter.name} (session commandes fermee)")
                pushNotice("envoi impossible : la session commandes est fermee")
                _status.update { it.copy(connected = false, connecting = true) }
            }

            SendResult.IO_ERROR -> {
                logFrame(frame, "CMD >", "ECHEC envoi vers ${shutter.name} (la passerelle n'a pas lu la trame)")
                pushNotice("envoi impossible : la passerelle n'a pas lu la trame")
            }
        }
    }

    /** Toutes les montees : commandes envoyees une par une, espacees de [GROUP_GAP_MS]. */
    fun allUp() = allMove(OwnMovement.UP)

    /** Toutes les descentes. */
    fun allDown() = allMove(OwnMovement.DOWN)

    /** Arrete tous les volets (annule aussi les arrets automatiques en attente). */
    fun allStop() {
        for (shutter in _volets.value) timers.remove(shutter.id)?.cancel()
        allMove(OwnMovement.STOP)
    }

    private fun allMove(action: OwnMovement) {
        val list = _volets.value.filter { it.configured }
        if (list.isEmpty()) {
            pushNotice("aucun volet configure : verifiez les adresses dans les reglages")
            return
        }
        pushNotice("${movementLabel(action)} pour ${list.size} volets")
        list.forEachIndexed { index, shutter ->
            scope.launch {
                if (index > 0) delay(index * GROUP_GAP_MS)
                sendMovement(shutter.id, action)
            }
        }
    }

    /**
     * Place un volet a un pourcentage estime (0 = ouvert, 100 = ferme) en envoyant
     * MONTER ou DESCENDRE, puis STOP au bout du temps calcule avec le temps de course.
     */
    fun setPercent(id: String, target: Int) {
        val shutter = find(id) ?: return
        if (!shutter.configured) {
            pushNotice("${shutter.name} : adresse non definie")
            return
        }
        val st = stateOf(id)
        val clamped = target.coerceIn(0, 100)
        if (clamped == st.currentPercent() && st.hasPosition) {
            stop(id)
            return
        }
        val action = if (clamped > st.currentPercent()) OwnMovement.DOWN else OwnMovement.UP
        val delta = kotlin.math.abs(clamped - st.currentPercent())
        val ms = (shutter.effectiveTravelMs() * delta / 100.0).toLong()
            .coerceIn(MIN_MOVE_MS, shutter.effectiveTravelMs())

        sendMovement(id, action)
        timers.remove(id)?.cancel()
        timers[id] = scope.launch {
            delay(ms)
            sendMovement(id, OwnMovement.STOP)
            updateState(id) { it.copy(positionPercent = clamped) }
        }
        pushNotice("${shutter.name} : vise $clamped % (mouvement calcule sur ${ms} ms)")
    }

    /**
     * Demande d'etat ponctuelle (`*#2*WHERE##`). La passerelle repond a part par
     * `*2*<etat>*WHERE##`, ce qui ressemble trait pour trait a un deplacement vu sur le
     * bus : l'adresse est donc memorisee pendant [STATUS_MATCH_MS] pour que la reponse
     * soit lue comme un etat et non comme un appui sur un interrupteur mural.
     */
    fun requestStatus(id: String) {
        val shutter = find(id) ?: return
        if (!shutter.configured) return
        val frame = OpenWebNet.requestStatus(shutter.where.toString())
        // queued = true : la passerelle repond a cette demande (rapport puis accuse). En
        // placant la trame dans la file du gateway, la reponse et l'accuse sont raccroches a
        // CETTE demande, avec une latence mesuree. En file, les 9 demandes sont traitees
        // dans l'ordre, donc la file FIFO reste juste.
        val sent = gateway.send(
            frame,
            queued = true,
            label = "demande d'etat -> ${shutter.name}",
        ) == SendResult.SENT
        logFrame(frame, "CMD >", if (sent) "demande d'etat -> ${shutter.name}" else "ECHEC envoi (session fermee)")
        if (sent) {
            pruneStatusRequests()
            statusRequests[shutter.where] = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------ surveillance bus

    /** vide la liste des adresses vues sur le bus. */
    fun clearObserved() {
        _observed.update { emptyList() }
        pushNotice("liste des adresses vues sur le bus remise a zero")
    }

    /** affecte une adresse vue sur le bus a un volet existant. */
    fun assignObserved(where: Int, id: String) {
        val shutter = find(id) ?: return
        setWhere(id, where)
        pushNotice("adresse $where affectee a ${shutter.name}")
    }

    /** cree un nouveau volet avec une adresse vue sur le bus. */
    fun addShutterFromObserved(where: Int) {
        val s = store.settings()
        addShutter(
            Shutter(
                id = "volet_$where",
                name = "Volet $where",
                room = s.defaultModel.label(),
                where = where,
                model = s.defaultModel,
                travelMs = s.defaultTravelMs,
            ),
        )
        pushNotice("nouveau volet cree avec l'adresse $where (type ${s.defaultModel.label()})")
    }

    // ------------------------------------------------------------- reglages

    /** reglages tels qu'enregistres (l'ecran les copie dans ses champs). */
    /** console de trafic : toutes les trames vues sur le port 20000 (la plus recente d'abord) */
    val trafficEntries: StateFlow<List<TrafficEntry>> get() = gateway.trafficEntries

    /** compteurs globaux de la console : trames envoyees, recues, echecs d'envoi */
    val trafficTotals: StateFlow<TrafficTotals> get() = gateway.trafficTotals

    /** vide la console de trafic */
    fun clearTraffic() = gateway.clearTraffic()

    fun currentSettings(): GatewaySettings = store.settings()

    /** enregistre des reglages sans reconnecter. */
    fun updateSettings(settings: GatewaySettings) {
        store.saveSettings(settings)
        pushNotice("reglages enregistres")
    }

    /** envoie une requete d'etat vers chaque volet configure, espacees de [GROUP_GAP_MS]. */
    fun refreshAllStatus() {
        val targets = _volets.value.filter { it.configured }
        if (targets.isEmpty()) {
            pushNotice("aucun volet configure : aucune requete envoyee")
            return
        }
        scope.launch {
            targets.forEachIndexed { index, shutter ->
                if (index > 0) delay(GROUP_GAP_MS)
                requestStatus(shutter.id)
            }
            pushNotice("${targets.size} requetes d'etat envoyees")
        }
    }

    fun rename(id: String, name: String, room: String) =
        updateShutter(id) { it.copy(name = name.trim(), room = room.trim()) }

    fun setWhere(id: String, where: Int) = updateShutter(id) { it.copy(where = where.coerceIn(0, MAX_WHERE)) }

    fun setTravel(id: String, travelMs: Long) =
        updateShutter(id) { it.copy(travelMs = travelMs.coerceIn(0, MAX_TRAVEL_MS)) }

    fun addShutter(shutter: Shutter) {
        val list = _volets.value + shutter
        _volets.update { list }
        store.save(list)
        _states.update { it + (shutter.id to ShutterState.IDLE) }
    }

    fun deleteShutter(id: String) {
        timers.remove(id)?.cancel()
        val list = _volets.value.filterNot { it.id == id }
        _volets.update { list }
        store.save(list)
        _states.update { it - id }
    }

    /** remet la liste des 9 volets avec les adresses par defaut. */
    fun resetDefaults() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        store.resetToDefault()
        _volets.update { ShutterDefaults.list() }
        _states.update { emptyMap() }
        pushNotice("liste des volets remise aux valeurs par defaut")
    }

    fun clearLog() {
        _log.update { emptyList() }
        _notices.update { emptyList() }
        rejected = 0
    }

    // ---------------------------------------------------------------- trames

    private fun onFrame(frame: OwnFrame) {
        val voletsNow = _volets.value
        val where = frame.whereAsInt

        // une reponse a notre requete d'etat ne doit pas etre lue comme un appui mural
        val statusReply = where > 0 && takeStatusRequest(where, frame)

        // session de reception reelle : CMD = reponse de la passerelle, MON = trame du bus
        logFrame(frame.raw, "${frame.session.ifEmpty { "?" }} <", interpret(frame, voletsNow, statusReply))

        if (frame.isNack) rejected += 1

        // toute trame d'automatisme (WHO=2) revele une adresse utilisee sur le bus
        if (frame.who == OpenWebNet.WHO_AUTOMATION) observeWhere(frame)

        if (statusReply) {
            applyStatusReply(where, frame, voletsNow)
            return
        }

        val movement = frame.movement
        if (frame.isShutterAction && movement != null) {
            val targets = voletsNow.filter { it.configured && it.where == where }
            for (shutter in targets) {
                // la session surveillance nous rend parfois nos propres ordres : une trame
                // identique a notre dernier ordre n'apprend rien, on l'ignore
                if (isOurEcho(shutter.id, movement)) {
                    pushNotice("${shutter.name} : trame recue identique a notre dernier ordre -> ignoree")
                    continue
                }
                if (movement == OwnMovement.STOP) timers.remove(shutter.id)?.cancel()
                // Un arret vu sur le bus dit seulement que le volet ne bouge plus : il ne dit
                // pas ou il se trouve. On note le mouvement, jamais une position.
                updateState(shutter.id) {
                    it.copy(
                        action = movement,
                        lastChangeAt = System.currentTimeMillis(),
                    )
                }
                pushNotice("${shutter.name} : ${movementLabel(movement)} recu du bus (interrupteur mural ?)")
            }
        }
    }

    /**
     * Vrai si la trame recue correspond a la reponse d'une requete d'etat envoyee il y a
     * moins de [STATUS_MATCH_MS] sur la meme adresse. L'en attente est alors retiree.
     */
    private fun takeStatusRequest(where: Int, frame: OwnFrame): Boolean {
        if (frame.who != OpenWebNet.WHO_AUTOMATION) return false
        val report = frame.isShutterStatusReport
        val state = !report && frame.movement != null
        if (!report && !state) return false
        val sentAt = statusRequests[where] ?: return false
        if (System.currentTimeMillis() - sentAt > STATUS_MATCH_MS) return false
        statusRequests.remove(where)
        return true
    }

    /** met a jour l'etat d'un volet a partir de la reponse a notre requete d'etat. */
    private fun applyStatusReply(where: Int, frame: OwnFrame, volets: List<Shutter>) {
        val movement = frame.movement ?: return
        val targets = volets.filter { it.configured && it.where == where }
        for (shutter in targets) {
            if (movement == OwnMovement.STOP) timers.remove(shutter.id)?.cancel()
            // La reponse a *#2*WHERE## ne donne que l'etat de marche (monte / arret / descente).
            // "arrete" ne veut pas dire "ferme" : on ne touche donc pas la position, qui reste
            // inconnue (-1) tant qu'aucune estimation locale n'a ete faite.
            updateState(shutter.id) {
                it.copy(
                    action = movement,
                    lastChangeAt = System.currentTimeMillis(),
                )
            }
            pushNotice("${shutter.name} : etat recu -> ${stateLabel(movement)}")
        }
    }

    /** oublie les demandes d'etat restees sans reponse. */
    private fun pruneStatusRequests() {
        val limit = System.currentTimeMillis() - STATUS_MATCH_MS
        val entries = statusRequests.entries.iterator()
        while (entries.hasNext()) {
            if (entries.next().value < limit) entries.remove()
        }
    }

    /** note une adresse "where" vue sur le bus (rapport, dispatch, interrupteur mural). */
    private fun observeWhere(frame: OwnFrame) {
        val where = frame.whereAsInt
        if (where <= 0) return
        val now = System.currentTimeMillis()
        _observed.update { list ->
            val existing = list.firstOrNull { it.where == where }
            val updated = ObservedWhere(
                where = where,
                seen = (existing?.seen ?: 0) + 1,
                lastRaw = frame.raw,
                lastAt = now,
            )
            (list.filterNot { it.where == where } + updated)
                .sortedByDescending { it.lastAt }
                .take(MAX_OBSERVED)
        }
    }

    /** texte du journal : ce que la trame apporte a l'utilisateur. */
    private fun interpret(frame: OwnFrame, volets: List<Shutter>, statusReply: Boolean = false): String {
        val owner = volets.firstOrNull { it.configured && it.where == frame.whereAsInt }
        val name = owner?.name ?: "adresse ${frame.where ?: "?"}"
        val movement = frame.movement
        return when {
            frame.isAck -> "accuse de reception"
            frame.isNack -> "commande REFUSEE par la passerelle"
            frame.isBusy -> "passerelle occupee"

            statusReply && movement != null ->
                "etat recu -> ${stateLabel(movement)} (reponse a notre demande) -> $name"

            statusReply ->
                "rapport recu (reponse a notre demande) valeurs=${frame.values.joinToString("/")} -> $name"

            frame.who == OpenWebNet.WHO_AUTOMATION && frame.report -> {
                if (frame.values.isEmpty()) "rapport volet DIM=${frame.dim ?: "?"} -> $name"
                else "rapport volet DIM=${frame.dim ?: "?"} valeurs=${frame.values.joinToString("/")} -> $name"
            }

            frame.who == OpenWebNet.WHO_AUTOMATION -> when (movement) {
                OwnMovement.UP -> "dispatch MONTER -> $name"
                OwnMovement.DOWN -> "dispatch DESCENDRE -> $name"
                OwnMovement.STOP -> "dispatch STOP -> $name"
                null -> "automatisme WHAT=${frame.what ?: "?"} -> $name"
            }

            frame.who == OpenWebNet.WHO_LIGHT -> "eclairage WHAT=${frame.what ?: "?"} -> ${frame.where ?: "?"}"
            frame.who == OpenWebNet.WHO_HVAC -> "confort (thermostat) ${frame.raw}"
            frame.who == OpenWebNet.WHO_SCENARIO -> "scenario ${frame.raw}"
            frame.who == OpenWebNet.WHO_CEN -> "CEN ${frame.raw}"
            frame.who == OpenWebNet.WHO_GATEWAY_MGMT -> "gestion passerelle ${frame.raw}"
            frame.report -> "rapport DIM=${frame.dim ?: "?"} valeurs=${frame.values.joinToString("/")} -> ${frame.where ?: "?"}"
            else -> "WHO=${frame.who} WHAT=${frame.what} WHERE=${frame.where ?: "?"}"
        }
    }

    private fun logFrame(raw: String, dir: String, text: String) {
        val now = System.currentTimeMillis()
        val entry = FrameLogEntry(now, TIME.format(Date(now)), raw, dir, text)
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
        Log.d(TAG, "[$dir] $raw :: $text")
    }

    private fun pushNotice(text: String) {
        val stamped = "${TIME.format(Date(System.currentTimeMillis()))}  $text"
        _notices.update { (listOf(stamped) + it).take(MAX_LOG) }
        Log.i(TAG, text)
    }

    // ---------------------------------------------------------------- outils

    private fun find(id: String): Shutter? = _volets.value.firstOrNull { it.id == id }

    /** etat en direct d'un volet (IDLE si jamais vu) */
    fun stateOf(id: String): ShutterState = _states.value[id] ?: ShutterState.IDLE

    /** nombre de NACK recus depuis le debut (diagnostic) */
    fun rejectedCount(): Int = rejected

    private fun updateState(id: String, transform: (ShutterState) -> ShutterState) {
        val next = transform(_states.value[id] ?: ShutterState.IDLE)
        _states.update { it + (id to next) }
    }

    private fun updateShutter(id: String, transform: (Shutter) -> Shutter) {
        val list = _volets.value.map { if (it.id == id) transform(it) else it }
        _volets.update { list }
        store.save(list)
    }

    private fun markBusy(id: String) {
        _busy.update { it + id }
        scope.launch {
            delay(BUSY_HINT_MS)
            _busy.update { it - id }
        }
    }

    /**
     * Meme motif, mais recu par la session surveillance : sert uniquement a ecarterr
     * l'echo de nos propres ordres quand la passerelle nous les redonne sur MON.
     */
    private fun isOurEcho(id: String, action: OwnMovement): Boolean = localOrders[id] == action

    private fun recordLocalOrder(id: String, action: OwnMovement) {
        localOrders[id] = action
    }

    /** trame OWN correspondant a un mouvement pour une adresse */
    private fun frameFor(action: OwnMovement, where: Int): String {
        val w = where.toString()
        return when (action) {
            OwnMovement.UP -> OpenWebNet.up(w)
            OwnMovement.DOWN -> OpenWebNet.down(w)
            OwnMovement.STOP -> OpenWebNet.stop(w)
        }
    }

    /**
     * position estimee juste apres un ordre de mouvement : on borne a 0/100, la valeur
     * est ensuite affinee par l'arret automatique (voir [setPercent]).
     */
    private fun estimatedAfter(shutter: Shutter, st: ShutterState, action: OwnMovement): Int {
        val current = st.currentPercent()
        return if (action == OwnMovement.DOWN) (current + FULL_MOVE_STEP_PCT).coerceIn(0, 100)
        else (current - FULL_MOVE_STEP_PCT).coerceIn(0, 100)
    }

    /** arret automatique programme au bout du temps de course */
    private fun startAutoStop(id: String) {
        val shutter = find(id) ?: return
        timers.remove(id)?.cancel()
        timers[id] = scope.launch {
            delay(shutter.effectiveTravelMs())
            sendMovement(id, OwnMovement.STOP)
            val atFullTravel = when (stateOf(id).action) {
                OwnMovement.UP -> 0
                else -> 100
            }
            updateState(id) { it.copy(positionPercent = atFullTravel) }
            pushNotice("${shutter.name} : arret automatique apres ${shutter.effectiveTravelMs() / 1000} s")
        }
    }

    private fun connectHint(gw: GatewayStatus): String = when {
        gw.connected && !gw.cmdConnected -> " : session commandes indisponible"
        gw.connected && !gw.monConnected -> " : surveillance du bus indisponible"
        else -> ""
    }

    /** arret de l'application : plus de timer, plus de session. */
    fun shutdown() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        gateway.disconnect()
    }

    companion object {
        private const val TAG = "OWN-repo"

        private const val MAX_LOG = 150
        private const val MAX_OBSERVED = 30
        private const val MAX_WHERE = 99

        /** temps de course maximum accepte : 2 min */
        private const val MAX_TRAVEL_MS = 120_000L

        /** temps entre deux commandes d'une action de groupe */
        private const val GROUP_GAP_MS = 120L

        /** fenetre pour rattacher une reponse de la surveillance a notre requete d'etat */
        private const val STATUS_MATCH_MS = 5_000L

        /** temps d'affichage du sablier "commande en cours de route" */
        private const val BUSY_HINT_MS = 600L

        /** deplacement minimal pour une position visee (0,5 s de moteur) */
        private const val MIN_MOVE_MS = 500L

        /** la position bouge de 100 % quand on envoie un ordre complet sans arret connu */
        private const val FULL_MOVE_STEP_PCT = 100

        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

        @Volatile
        private var instance: VoletsRepository? = null

        /** singleton global : l'etat est partage entre les ecrans et les rotations */
        fun get(context: Context): VoletsRepository =
            instance ?: synchronized(this) {
                instance ?: VoletsRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** Mise a jour atomique (compare-and-set) d'un StateFlow. */
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val old = value
        if (compareAndSet(old, transform(old))) return
    }
}
