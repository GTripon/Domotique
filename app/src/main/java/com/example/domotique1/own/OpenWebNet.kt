package com.example.domotique1.own

/**
 * OpenWebNet (OWN / LEGANTIC) : trames texte, separateur '*', fin '##'.
 *
 * Reference : spec OPEN + implementation de reference openwebnet4j (mvalla/openwebnet4j).
 *
 * Grammaire (verifiee dans BaseOpenMessage.getPartsStrings/parseParts) :
 * - commande / dispatch bus : `*WHO*WHAT*WHERE##`   (ex. `*2*1*31##`)
 * - commande traduite       : `*WHO*SIGN#WHAT*WHERE##` (ex. `*2*1000#0*55##`)
 * - rapport d'etat          : `*#WHO*WHERE*DIM*VAL1*VAL2...##` (ex. `*#2*55*10*10*100*0*0##`)
 * - ACK / NACK / BUSY       : `*#*1##` / `*#*0##` / `*#*6##`
 */
object OpenWebNet {

    const val FRAME_END = "##"
    const val FRAME_START = "*"
    const val FRAME_SPLIT = "*"

    /** ACK : confirmation de bonne reception */
    const val FRAME_ACK = "*#*1##"

    /** NACK : commande refusee */
    const val FRAME_NACK = "*#*0##"

    /** gateway occupee */
    const val FRAME_BUSY = "*#*6##"

    /** debut commun aux trames ACK / NACK / BUSY */
    const val FRAME_ACK_NACK_BUSY_START = "*#*"

    /** etiquettes de session, identiques a celles de la console de trafic */
    const val SESSION_CMD = "CMD"
    const val SESSION_MON = "MON"

    /** debut des trames "dimensionnees" (rapports d'etat) */
    const val FRAME_START_DIM = "*#"

    // --- Ouverture de session ---
    const val CMD_SESSION = "*99*0##" // session commandes
    const val MON_SESSION = "*99*1##" // session surveillance (evenements du bus)

    // --- WHO (scenarios utiles a l'application) ---
    const val WHO_SCENARIO = 0
    const val WHO_LIGHT = 1

    // --- WHO=2 automation (volets) ---
    const val WHO_AUTOMATION = 2
    const val WHO_LOAD = 3
    const val WHO_HVAC = 4
    const val WHO_AUX = 9
    const val WHO_GATEWAY_MGMT = 13
    const val WHO_CEN = 15
    const val WHO_ENERGY = 18

    const val WHAT_AUTOMATION_STOP = 0
    const val WHAT_AUTOMATION_UP = 1
    const val WHAT_AUTOMATION_DOWN = 2

    // --- DIM des rapports automation ---
    /** etat du volet (actuateur avance) : 4 valeurs (statut, course, niveau, blocage) */
    const val DIM_SHUTTER_STATUS = 10

    /** consigne de niveau / position (actuateur avance) */
    const val DIM_GOTO_LEVEL = 11

    /** adresse generale : tous les volets */
    const val WHERE_GENERAL = "0"

    fun stop(where: String) = "$FRAME_START$WHO_AUTOMATION*$WHAT_AUTOMATION_STOP*$where$FRAME_END"

    fun up(where: String) = "$FRAME_START$WHO_AUTOMATION*$WHAT_AUTOMATION_UP*$where$FRAME_END"

    fun down(where: String) = "$FRAME_START$WHO_AUTOMATION*$WHAT_AUTOMATION_DOWN*$where$FRAME_END"

    /** Demande d'etat : `*#2*WHERE##` (WHERE="0" = tous les volets) */
    fun requestStatus(where: String) = "$FRAME_START#$WHO_AUTOMATION*$where$FRAME_END"
}

/** Mouvement d'un volet tel que vu sur le bus. */
enum class OwnMovement { UP, DOWN, STOP }

/**
 * Trame OWN analysee de facon tolerante : toute trame inconnue est gardee brute
 * (who == null) sans lever d'exception.
 */
data class OwnFrame(
    val raw: String,
    val who: Int? = null,
    /** commande : 0 = arret, 1 = montee, 2 = descente ; null pour un rapport */
    val what: Int? = null,
    /** adresse "WHERE" brute ("31", "013", "#15"...) */
    val where: String? = null,
    /** DIM d'un rapport (10 = etat volet, 11 = niveau) */
    val dim: Int? = null,
    /** valeurs du rapport apres la DIM */
    val values: List<String> = emptyList(),
    /** true si trame de rapport (`*#WHO*...##`) */
    val report: Boolean = false,
    val ack: Boolean = false,
    val nack: Boolean = false,
    val busy: Boolean = false,
    /** session de reception ("CMD" ou "MON"), ajoutee par MyHomeGateway a la lecture */
    val session: String = "",
) {
    val isAck: Boolean get() = ack
    val isNack: Boolean get() = nack
    val isBusy: Boolean get() = busy
    val isAckNackBusy: Boolean get() = ack || nack || busy

    /** vraie si la trame a pu etre decodee */
    val valid: Boolean get() = who != null || isAckNackBusy

    val isAutomation: Boolean get() = who == OpenWebNet.WHO_AUTOMATION

    /**
     * Code WHERE entier de la trame (-1 si la trame n'a pas d'adresse numerique,
     * ex. `*#2*##` ou adresse `#15`).
     */
    val whereAsInt: Int
        get() {
            val w = normalizeWhere(where) ?: return -1
            return w.removePrefix("#").toIntOrNull() ?: -1
        }

    /** rapport d'etat volet : `*#2*WHERE*10*...##` */
    val isShutterStatusReport: Boolean
        get() = report && isAutomation && dim == OpenWebNet.DIM_SHUTTER_STATUS

    /** commande / dispatch decrivant un mouvement (`*2*0|1|2*WHERE##`) */
    val movement: OwnMovement?
        get() = when (what) {
            OpenWebNet.WHAT_AUTOMATION_UP -> OwnMovement.UP
            OpenWebNet.WHAT_AUTOMATION_DOWN -> OwnMovement.DOWN
            OpenWebNet.WHAT_AUTOMATION_STOP -> OwnMovement.STOP
            else -> null
        }

    /** une trame qui parle d'un mouvement de volet */
    val isShutterAction: Boolean get() = isAutomation && movement != null && where != null

    companion object {

        /**
         * Analyse une trame complete (terminee par '##'). Ne leve jamais d'exception :
         * sur trame invalide, [raw] est conserve et [valid] vaut false.
         */
        fun parse(frame: String): OwnFrame {
            val f = frame.trim()
            if (f.isEmpty()) return OwnFrame(f)

            when (f) {
                OpenWebNet.FRAME_ACK -> return OwnFrame(f, ack = true)
                OpenWebNet.FRAME_NACK -> return OwnFrame(f, nack = true)
                OpenWebNet.FRAME_BUSY -> return OwnFrame(f, busy = true)
            }

            // ACK/NACK/BUSY avec une variante (ex. `*#*3##`) : on garde le code
            if (f.startsWith(OpenWebNet.FRAME_ACK_NACK_BUSY_START)) {
                val code = f.removePrefix(OpenWebNet.FRAME_ACK_NACK_BUSY_START)
                    .removeSuffix(OpenWebNet.FRAME_END)
                return when (code) {
                    "1" -> OwnFrame(f, ack = true)
                    "0" -> OwnFrame(f, nack = true)
                    "6" -> OwnFrame(f, busy = true)
                    else -> OwnFrame(f)
                }
            }

            val body = f.removeSuffix(OpenWebNet.FRAME_END)
            if (!body.startsWith(OpenWebNet.FRAME_START)) return OwnFrame(f)

            val p = body.split(OpenWebNet.FRAME_SPLIT)
            if (p.size < 2) return OwnFrame(f)

            val whoToken = p[1]
            val report = whoToken.startsWith("#")
            val who = whoToken.removePrefix("#").toIntOrNull() ?: return OwnFrame(f)

            if (report) {
                // *#WHO*WHERE*DIM*VAL1*VAL2...##
                val where = p.getOrNull(2)?.takeIf { it.isNotEmpty() }
                val dim = p.getOrNull(3)?.removePrefix("#")?.toIntOrNull()
                val values = if (p.size > 4) p.subList(4, p.size) else emptyList()
                return OwnFrame(f, who = who, where = where, dim = dim, values = values, report = true)
            }

            // *WHO*WHAT*WHERE## (WHAT peut etre traduit : "1000#0")
            val whatToken = p.getOrNull(2) ?: return OwnFrame(f)
            val what = if (whatToken.contains("#")) {
                whatToken.substringAfter("#").toIntOrNull()
            } else {
                whatToken.toIntOrNull()
            }
            val where = p.getOrNull(3)?.takeIf { it.isNotEmpty() }
            return OwnFrame(f, who = who, what = what, where = where, report = false)
        }

        /** "31", "013", "#15"... -> code WHERE normalise (les 0 de tete sont conserves) */
        fun normalizeWhere(raw: String?): String? {
            val w = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (w.all { it.isDigit() } || (w.length > 1 && w[0] == '#' && w.substring(1).all { it.isDigit() })) w else null
        }

        /** deux trames identiques (meme WHERE / meme mouvement) ? */
        fun sameMeaning(a: OwnFrame, b: OwnFrame): Boolean =
            a.report == b.report && a.who == b.who && a.what == b.what && a.where == b.where &&
                a.dim == b.dim && a.values == b.values
    }
}
