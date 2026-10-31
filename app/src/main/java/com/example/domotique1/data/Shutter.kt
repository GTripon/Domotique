package com.example.domotique1.data

import com.example.domotique1.own.OwnMovement

/**
 * Type de volet : sert uniquement a ranger les volets et a generer les identifiants.
 * L'installation compte 3 chambres, 1 cuisine et 5 salons.
 */
enum class ShutterModel {
    CHAMBRE,
    CUISINE,
    SALON,
}

/** Libelle francais d'un type de volet (affiche dans les listes deroulantes). */
fun ShutterModel.label(): String = when (this) {
    ShutterModel.CHAMBRE -> "Chambre"
    ShutterModel.CUISINE -> "Cuisine"
    ShutterModel.SALON -> "Salon"
}

/** Repere un type de volet a partir du nom enregistre (valeur par defaut si inconnu). */
fun shutterModelFromName(name: String): ShutterModel = modelFromName(name)

/**
 * Un volet roulant du bus OpenWebNet (WHO=2) : configuration enregistree localement.
 *
 * [where] est l'adresse OpenWebNet reelle (1 a 15 en plan/point, 16 a 99 en numero de
 * ligne, ou 21 a 25 pour les adresses de groupe). 0 veut dire "non configure" :
 * l'application n'enverra aucune commande vers ce volet.
 *
 * Le bus ne connait pas la position des volets : [travelMs] est le temps de course
 * total (monte -> descendu) qui sert a estimer le pourcentage de fermeture.
 */
data class Shutter(
    val id: String,
    val name: String,
    val room: String,
    val where: Int = 0,
    val model: ShutterModel = ShutterModel.CHAMBRE,
    val travelMs: Long = 0,
) {
    /** vrai quand une adresse exploitable est enregistree */
    val configured: Boolean get() = where > 0

    /** temps de course total a utiliser (valeur par defaut si non regle) */
    fun effectiveTravelMs(): Long = if (travelMs > 0) travelMs else DEFAULT_TRAVEL_MS

    companion object {
        /** duree de course par defaut : 45 s (a ajuster volet par volet dans les reglages) */
        const val DEFAULT_TRAVEL_MS = 45_000L
    }
}

/**
 * Etat "en direct" d'un volet (non enregistre : le bus ne le fournit pas).
 *
 * [action] est le dernier mouvement connu : [OwnMovement.UP], [OwnMovement.DOWN] ou
 * [OwnMovement.STOP]. [positionPercent] vaut -1 quand la position est inconnue
 * (aucune commande envoyee depuis le demarrage de l'application).
 */
data class ShutterState(
    val action: OwnMovement = OwnMovement.STOP,
    val positionPercent: Int = -1,
    val lastChangeAt: Long = 0,
) {
    /** le volet est-il en train de monter ou descendre ? */
    val isMoving: Boolean get() = action == OwnMovement.UP || action == OwnMovement.DOWN

    /** la position est-elle connue (au moins une commande envoyee) ? */
    val hasPosition: Boolean get() = positionPercent >= 0

    /** position plancher (0 si inconnue) : sert aux calculs de deplacement partiel */
    fun currentPercent(): Int = if (positionPercent < 0) 0 else positionPercent

    companion object {
        /** etat par defaut : jamais vu, position inconnue */
        val IDLE = ShutterState()
    }
}

/** Libelle francais d'un mouvement Own. */
fun movementLabel(action: OwnMovement): String = when (action) {
    OwnMovement.UP -> "MONTER"
    OwnMovement.DOWN -> "DESCENDRE"
    OwnMovement.STOP -> "STOP"
}

/**
 * Libelle francais d'un etat lu sur le bus (WHAT 0 = arret, 1 = en montee, 2 = en
 * descente). Contrairement a [movementLabel], on decrit ce que fait le volet.
 */
fun stateLabel(action: OwnMovement?): String = when (action) {
    OwnMovement.UP -> "en montee"
    OwnMovement.DOWN -> "en descente"
    OwnMovement.STOP -> "arrete"
    null -> "etat inconnu"
}

/** Les 9 volets de l'installation, tels qu'enregistres a la premiere utilisation. */
object ShutterDefaults {

    fun list(): List<Shutter> = DEFAULT

    /**
     * Adresses mises par defaut : elles donnent deja un resultat sur cette installation.
     * Les adresses reellement utilisees par l'installateur se lisent dans la carte
     * "Surveillance du bus" des reglages, puis se corrigent a la main si besoin.
     */
    val DEFAULT: List<Shutter> = listOf(
        Shutter("chambre_1", "Chambre 1", "Chambre", 51, ShutterModel.CHAMBRE),
        Shutter("chambre_2", "Chambre 2", "Chambre", 52, ShutterModel.CHAMBRE),
        Shutter("chambre_3", "Chambre 3", "Chambre", 53, ShutterModel.CHAMBRE),
        Shutter("cuisine", "Cuisine", "Cuisine", 56, ShutterModel.CUISINE),
        Shutter("salon_1", "Salon 1", "Salon", 57, ShutterModel.SALON),
        Shutter("salon_2", "Salon 2", "Salon", 58, ShutterModel.SALON),
        Shutter("salon_3", "Salon 3", "Salon", 59, ShutterModel.SALON),
        Shutter("salon_4", "Salon 4", "Salon", 67, ShutterModel.SALON),
        Shutter("salon_5", "Salon 5", "Salon", 68, ShutterModel.SALON),
    )
}
