package com.example.domotique1.own

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Une trame qui a traverse le port 20000, dans un sens ou dans l'autre.
 *
 * C'est la source de la console de debug : on y voit litteralement ce qui est ecrit
 * sur le socket vers la passerelle et ce qu'elle renvoie.
 */
data class TrafficEntry(
    val at: Long,
    /** "CMD" (session commandes) ou "MON" (session surveillance) */
    val session: String,
    /** true = envoye par l'application, false = recu de la passerelle */
    val outgoing: Boolean,
    /** nature technique de la trame : OUVERTURE / COMMANDE / KEEPALIVE / ENVOI ECHOUE / RAFFALE / RECEPTION */
    val kind: String,
    /** trame OPENWEBNET brute, exactement comme elle a ete ecrite ou lue */
    val raw: String,
    /** complement lisible par l'utilisateur (libelle de la commande, latence...) */
    val note: String = "",
) {
    /** fleche de sens pour l'affichage : vers la passerelle ou depuis la passerelle */
    val arrow: String get() = if (outgoing) ">>" else "<<"

    /** sens en toutes lettres, pour les filtres ou le detail */
    val directionLabel: String get() = if (outgoing) "envoye" else "recu"

    /** heure d'horloge de la trame (HH:mm:ss) */
    fun clock(): String = TIME.format(Date(at))

    /** ligne complete, telle qu'affichee dans la console */
    fun line(): String = "${clock()}  $session $arrow $kind  $raw" + if (note.isEmpty()) "" else "  [$note]"

    companion object {
        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

        // types de trames utilises par la console
        const val OPEN = "OUVERTURE"
        const val COMMAND = "COMMANDE"
        const val KEEPALIVE = "KEEPALIVE"
        const val FAILED = "ENVOI ECHOUE"
        const val INCOMING = "RECEPTION"
        const val BURST = "RAFFALE"
    }
}

/**
 * Journal partage ou sont ecrites toutes les trames echangees avec la passerelle
 * (les deux sessions CMD et MON utilisent le meme objet).
 *
 * Les entrees sont gardees du plus recent au plus ancien, dans une liste immuable :
 * une vue peut donc la collecter directement avec [collectAsState] sans rien perdre.
 */
class TrafficBus(private val capacity: Int = DEFAULT_CAPACITY) {

    private val _entries = MutableStateFlow(emptyList<TrafficEntry>())
    val entries: StateFlow<List<TrafficEntry>> = _entries.asStateFlow()

    /** compteurs globaux + dernier envoi / derniere reception (la liste, elle, est bornee) */
    private val _totals = MutableStateFlow(TrafficTotals())
    val totals: StateFlow<TrafficTotals> = _totals.asStateFlow()

    /**
     * Ajoute une trame. Appelee depuis les threads de lecture/ecriture des sessions :
     * compare-and-set uniquement, jamais de blocage.
     */
    fun record(session: String, outgoing: Boolean, kind: String, raw: String, note: String = "") {
        val now = System.currentTimeMillis()
        val entry = TrafficEntry(now, session, outgoing, kind, raw, note)
        _entries.update { (listOf(entry) + it).take(capacity) }
        _totals.update { prev ->
            val failed = kind == TrafficEntry.FAILED
            if (outgoing) {
                prev.copy(
                    sent = prev.sent + 1,
                    errors = prev.errors + if (failed) 1 else 0,
                    lastSentAt = now,
                    lastSent = raw,
                    lastSentKind = kind,
                    lastSentSession = session,
                    lastSentNote = note,
                    lastSentFailed = failed,
                )
            } else {
                prev.copy(
                    received = prev.received + 1,
                    lastReceivedAt = now,
                    lastReceived = raw,
                    lastReceivedSession = session,
                    lastReceivedKind = kind,
                )
            }
        }
    }

    /** vide la console (les compteurs, eux, ne bougent pas). */
    fun clear() {
        _entries.update { emptyList() }
    }

    companion object {
        /** nombre de trames conservees en memoire pour la console */
        const val DEFAULT_CAPACITY = 300
    }
}

/** Compteurs globaux de trafic, independants de la liste affichee. */
data class TrafficTotals(
    val sent: Int = 0,
    val received: Int = 0,
    val errors: Int = 0,
    val lastSentAt: Long = 0,
    val lastSent: String = "",
    val lastSentKind: String = "",
    val lastSentSession: String = "",
    val lastSentNote: String = "",
    val lastSentFailed: Boolean = false,
    val lastReceivedAt: Long = 0,
    val lastReceived: String = "",
    val lastReceivedSession: String = "",
    val lastReceivedKind: String = "",
) {
    /** vrai si une trame est passee dans les deux sens il y a moins de [windowMs] */
    fun aliveRecently(windowMs: Long = 60_000): Boolean {
        val now = System.currentTimeMillis()
        return now - lastSentAt < windowMs || now - lastReceivedAt < windowMs
    }
}

/** Mise a jour atomique (compare-and-set) d'un StateFlow. */
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val old = value
        if (compareAndSet(old, transform(old))) return
    }
}
