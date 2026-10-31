package com.example.domotique1.data

import android.content.Context
import androidx.core.content.edit

/** Repere un type de volet a partir du nom enregistre (valeur par defaut si inconnu). */
internal fun modelFromName(name: String): ShutterModel =
    ShutterModel.values().firstOrNull { it.name == name } ?: ShutterModel.CHAMBRE

/**
 * Parametres de connexion a la passerelle MyHome.
 *
 * Ce sont de simples valeurs : l'ecran Reglages les lit avec
 * [VoletsRepository.currentSettings] et les enregistre avec
 * [VoletsRepository.updateSettings] ou [VoletsRepository.applySettingsAndConnect].
 */
data class GatewaySettings(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    /** mode essai : les commandes sont affichees dans le journal mais jamais envoyees */
    val dryRun: Boolean = false,
    /** temps de course applique aux nouveaux volets (ms) */
    val defaultTravelMs: Long = Shutter.DEFAULT_TRAVEL_MS,
    /** type propose quand on cree un volet depuis une adresse vue sur le bus */
    val defaultModel: ShutterModel = ShutterModel.CHAMBRE,
) {
    companion object {
        const val DEFAULT_HOST = "192.168.1.143"
        const val DEFAULT_PORT = 20000
    }
}

/**
 * Persistance locale (SharedPreferences) : reglages de la passerelle + liste des volets.
 *
 * Format d'une ligne de volet, champs separes par '|':
 * `id|nom|piece|where|modele|tempsDeCourse`
 * Les champs manquants prennent les valeurs par defaut (utile quand le format evolue).
 * L'etat en direct (mouvement, position estimee) n'est pas enregistre : le bus ne le
 * fournit pas et il serait faux apres un redemarrage.
 */
class ShutterStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- reglages

    fun settings(): GatewaySettings = GatewaySettings(
        host = prefs.getString(KEY_HOST, GatewaySettings.DEFAULT_HOST) ?: GatewaySettings.DEFAULT_HOST,
        port = prefs.getInt(KEY_PORT, GatewaySettings.DEFAULT_PORT),
        dryRun = prefs.getBoolean(KEY_DRY_RUN, false),
        defaultTravelMs = prefs.getLong(KEY_DEFAULT_TRAVEL, Shutter.DEFAULT_TRAVEL_MS),
        defaultModel = modelFromName(prefs.getString(KEY_DEFAULT_MODEL, "") ?: ""),
    )

    fun saveSettings(s: GatewaySettings) {
        prefs.edit {
            putString(KEY_HOST, s.host.trim())
            putInt(KEY_PORT, s.port)
            putBoolean(KEY_DRY_RUN, s.dryRun)
            putLong(KEY_DEFAULT_TRAVEL, s.defaultTravelMs)
            putString(KEY_DEFAULT_MODEL, s.defaultModel.name)
        }
    }

    // ------------------------------------------------------------------ volets

    /** liste enregistree, ou les 9 volets par defaut si rien n'est enregistre */
    fun load(): List<Shutter> {
        val raw = prefs.getString(KEY_VOLETS, null) ?: return ShutterDefaults.list()
        if (raw.isBlank()) return ShutterDefaults.list()
        val decoded = raw.split(LINE).mapNotNull { decodeShutter(it) }
        return if (decoded.isEmpty()) ShutterDefaults.list() else decoded
    }

    fun save(list: List<Shutter>) {
        prefs.edit { putString(KEY_VOLETS, list.joinToString(LINE) { encodeShutter(it) }) }
    }

    /** efface la liste enregistree : [load] renverra de nouveau les valeurs par defaut */
    fun resetToDefault() {
        prefs.edit { putString(KEY_VOLETS, null) }
    }

    // -------------------------------------------------------------- encodage

    private fun encodeShutter(s: Shutter): String = listOf(
        clean(s.id),
        clean(s.name),
        clean(s.room),
        s.where,
        s.model.name,
        s.travelMs,
    ).joinToString(SEP)

    private fun decodeShutter(line: String): Shutter? {
        val p = line.split(SEP)
        if (p.size < 4) return null
        val id = p[0].trim()
        if (id.isEmpty()) return null
        return Shutter(
            id = id,
            name = p[1].trim(),
            room = p[2].trim(),
            where = p[3].toIntOrNull() ?: 0,
            model = modelFromName(p.getOrElse(4) { ShutterModel.CHAMBRE.name }.trim()),
            travelMs = p.getOrElse(5) { "0" }.toLongOrNull() ?: 0L,
        )
    }

    /** un nom de volet ne doit pas contenir les separateurs du format */
    private fun clean(value: String): String = value.trim().replace(SEP, "/").replace(LINE, " ")

    companion object {
        private const val PREFS = "domotique_gw"
        private const val SEP = "|"
        private const val LINE = "\n"

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_DEFAULT_TRAVEL = "default_travel"
        private const val KEY_DEFAULT_MODEL = "default_model"
        private const val KEY_VOLETS = "volets"
    }
}
