package com.example.domotique1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domotique1.R
import com.example.domotique1.data.GatewaySettings
import com.example.domotique1.data.ObservedWhere
import com.example.domotique1.data.Shutter
import com.example.domotique1.data.ShutterModel
import com.example.domotique1.data.VoletsRepository
import com.example.domotique1.data.label
import com.example.domotique1.own.TrafficEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

private fun timeOf(millis: Long): String =
    if (millis <= 0L) "-" else CLOCK.format(Date(millis))

/** Duree en millisecondes, tel que le reseau le repond (le point d'interrogation = jamais mesure). */
private fun msText(millis: Long): String = if (millis < 0L) "-" else "$millis ms"

/**
 * Ecran de reglages, en trois parties :
 *  1. connexion a la passerelle + etat reel des deux sessions (diagnostic)
 *  2. surveillance du bus : les adresses "where" vues sur le bus, a affecter aux volets
 *  3. liste des volets : nom, piece, adresse, temps de course + journal des trames
 *
 * Aucune adresse n'est recherchee en envoyant des ordres de test : on note simplement
 * ce que la passerelle et les interrupteurs muraux envoient deja sur le bus.
 */

@Composable

fun SettingsScreen(repository: VoletsRepository, onBack: () -> Unit) {
    val volets by repository.volets.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { GatewayCard(repository) }
            item { LinkCard(repository) }
            item { TrafficConsoleCard(repository) }
            item { BusWatchCard(repository) }
            item { VoletsHeaderCard(repository) }

            items(volets, key = { "editeur_" + it.id }) { shutter ->
                ShutterEditorCard(repository, shutter)
            }

            item { AddShutterCard(repository) }
            item { LogCard(repository) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ------------------------------------------------------------------ passerelle

/** Adresse, port, mode essai, reglages par defaut des volets. */
@Composable
private fun GatewayCard(repository: VoletsRepository) {
    val saved = repository.currentSettings()
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var dryRun by remember { mutableStateOf(saved.dryRun) }
    var travel by remember { mutableStateOf((saved.defaultTravelMs / 1000).toString()) }
    var model by remember { mutableStateOf(saved.defaultModel) }
    var feedback by remember { mutableStateOf<String?>(null) }

    // stringResource ne peut pas etre appele dans un onClick : on prepare les textes
    val msgSaved = stringResource(R.string.settings_saved)
    val msgReconnected = stringResource(R.string.settings_reconnected)

    SettingsCard(title = stringResource(R.string.settings_gateway)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.settings_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { value -> port = value.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.settings_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = travel,
            onValueChange = { value -> travel = value.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.settings_default_travel)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.settings_default_model),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        ModelPicker(selected = model, onSelect = { model = it })

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dryRun, onCheckedChange = { dryRun = it })
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_dry_run))
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    repository.updateSettings(buildSettings(host, port, dryRun, travel, model))
                    feedback = msgSaved
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_save)) }

            Button(
                onClick = {
                    repository.applySettingsAndConnect(
                        buildSettings(host, port, dryRun, travel, model)
                    )
                    feedback = msgReconnected
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_save_connect)) }
        }

        feedback?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** reconstruit des reglages complets a partir des champs de l'ecran. */
private fun buildSettings(
    host: String,
    port: String,
    dryRun: Boolean,
    travelSeconds: String,
    model: ShutterModel,
): GatewaySettings {
    val base = GatewaySettings()
    return GatewaySettings(
        host = host.trim().ifBlank { base.host },
        port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: base.port,
        dryRun = dryRun,
        defaultTravelMs = (travelSeconds.toLongOrNull() ?: (base.defaultTravelMs / 1000)) * 1000,
        defaultModel = model,
    )
}

/** Chambre / Cuisine / Salon : type par defaut des nouveaux volets. */
@Composable
private fun ModelPicker(selected: ShutterModel, onSelect: (ShutterModel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ShutterModel.entries.forEach { model ->
            if (model == selected) {
                Button(onClick = { onSelect(model) }, modifier = Modifier.weight(1f)) {
                    Text(model.label())
                }
            } else {
                OutlinedButton(onClick = { onSelect(model) }, modifier = Modifier.weight(1f)) {
                    Text(model.label())
                }
            }
        }
    }
}

// --------------------------------------------------------------- etat liaison

/**
 * Etat reel des deux sessions TCP + compteurs + dernieres trames : c'est la carte
 * a regarder quand un volet ne reagit pas (accuse en retard, session muette...).
 */
@Composable
private fun LinkCard(repository: VoletsRepository) {
    val status by repository.status.collectAsState()
    val gw = status.gateway

    SettingsCard(title = stringResource(R.string.settings_link)) {
        InfoRow(stringResource(R.string.settings_target), "${gw.host}:${gw.port}")
        InfoRow(stringResource(R.string.settings_cmd_session), yesNo(gw.cmdConnected))
        InfoRow(stringResource(R.string.settings_mon_session), yesNo(gw.monConnected))
        InfoRow(stringResource(R.string.settings_cmd_tx), gw.cmdTx.toString())
        InfoRow(stringResource(R.string.settings_cmd_rx), gw.cmdRx.toString())
        InfoRow(stringResource(R.string.settings_mon_rx), gw.monRx.toString())
        InfoRow(stringResource(R.string.settings_keepalives), gw.keepalives.toString())
        InfoRow(stringResource(R.string.settings_keepalive_acks), gw.keepaliveAcks.toString())
        InfoRow(
            label = stringResource(R.string.settings_keepalive_latency),
            value = msText(gw.lastKeepaliveRttMs),
        )
        InfoRow(stringResource(R.string.settings_reconnects), gw.reconnects.toString())
        InfoRow(stringResource(R.string.settings_nack_count), repository.rejectedCount().toString())
        InfoRow(
            label = stringResource(R.string.settings_last_sent),
            value = "${gw.lastSentFrame}  ${timeOf(gw.lastSentAt)}",
        )
        InfoRow(
            label = stringResource(R.string.settings_last_rx),
            value = "${gw.lastFrame}  ${timeOf(gw.lastRxAt)}",
        )
        InfoRow(
            label = stringResource(R.string.settings_ack_latency),
            value = msText(gw.lastAckLatencyMs),
            highlight = gw.ackIsDelayed,
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = repository::reconnect,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_reconnect)) }

            if (status.connected) {
                OutlinedButton(
                    onClick = repository::disconnect,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_disconnect)) }
            } else {
                Button(onClick = repository::connect, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_connect))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = repository::refreshAllStatus,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_refresh_status)) }
            OutlinedButton(
                onClick = repository::clearLog,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_clear_log)) }
        }
    }
}

private fun yesNo(value: Boolean): String = if (value) "oui" else "non"

// -------------------------------------------------------- console des trames

/**
 * Console complete : tout ce qui a traverse le port 20000 depuis le debut, dans les
 * deux sens, horodate. C'est ce qui permet de verifier litteralement la commande
 * partie vers la passerelle et la reponse qu'elle a fini par renvoyer.
 */
@Composable
private fun TrafficConsoleCard(repository: VoletsRepository) {
    val entries by repository.trafficEntries.collectAsState()
    val totals by repository.trafficTotals.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(title = stringResource(R.string.traffic_console)) {
        Text(
            text = stringResource(R.string.settings_traffic_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.traffic_summary, totals.sent, totals.received, totals.errors),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )

        if (totals.lastSent.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = ">>  ${totals.lastSentSession}  ${totals.lastSent}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (totals.lastSentFailed) Color(0xFFD32F2F) else Color(0xFF1565C0),
            )
            Text(
                text = buildString {
                    append(totals.lastSentKind)
                    append("  ${timeOf(totals.lastSentAt)}")
                    if (totals.lastSentNote.isNotEmpty()) append("  -  ${totals.lastSentNote}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (totals.lastReceived.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "<<  ${totals.lastReceivedSession}  ${totals.lastReceived}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF2E7D32),
            )
            Text(
                text = "${totals.lastReceivedKind}  ${timeOf(totals.lastReceivedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.traffic_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.traffic_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.traffic_col_head),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            val shown = if (expanded) entries else entries.take(12)
            for (entry in shown) {
                TrafficRow(entry)
            }

            Spacer(Modifier.height(4.dp))
            if (entries.size > shown.size) {
                TextButton(
                    onClick = { expanded = true },
                    colors = ButtonDefaults.textButtonColors(),
                ) { Text(stringResource(R.string.traffic_show_more)) }
            } else if (expanded && entries.size > 12) {
                TextButton(
                    onClick = { expanded = false },
                    colors = ButtonDefaults.textButtonColors(),
                ) { Text(stringResource(R.string.traffic_show_less)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                repository.clearTraffic()
                expanded = false
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.traffic_clear)) }
    }
}

/** Une ligne de la console : heure, session, sens, nature, trame brute, libelle. */
@Composable
private fun TrafficRow(entry: TrafficEntry) {
    val accent = when {
        entry.kind == TrafficEntry.FAILED -> Color(0xFFD32F2F)
        entry.outgoing -> Color(0xFF1565C0)
        else -> Color(0xFF2E7D32)
    }
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.clock(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.arrow,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.session,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.kind,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        Text(
            text = entry.raw,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (entry.note.isNotEmpty()) {
            Text(
                text = entry.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------ surveillance bus

/**
 * Adresses de volet vues sur le bus depuis le demarrage de l'application
 * (interrupteurs muraux, rapports de la passerelle). Aucune commande de test
 * n'est envoyee : on note ce qui circule deja.
 */
@Composable
private fun BusWatchCard(repository: VoletsRepository) {
    val observed by repository.observed.collectAsState()
    val volets by repository.volets.collectAsState()
    var menuFor by remember { mutableStateOf<Int?>(null) }

    SettingsCard(title = stringResource(R.string.settings_bus_watch)) {
        Text(
            text = stringResource(R.string.settings_bus_watch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (observed.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_observed),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            observed.forEach { entry ->
                ObservedRow(
                    entry = entry,
                    volets = volets,
                    expanded = menuFor == entry.where,
                    onExpand = { menuFor = if (menuFor == entry.where) null else entry.where },
                    onDismiss = { menuFor = null },
                    repository = repository,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = repository::clearObserved, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_clear_observed))
        }
    }
}

@Composable
private fun ObservedRow(
    entry: ObservedWhere,
    volets: List<Shutter>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    repository: VoletsRepository,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "WHERE ${entry.where}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.settings_seen, entry.seen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${entry.lastRaw}   ${timeOf(entry.lastAt)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    TextButton(onClick = onExpand, enabled = volets.isNotEmpty()) {
                        Text(stringResource(R.string.settings_assign))
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = onDismiss,
                    ) {
                        volets.forEach { shutter ->
                            DropdownMenuItem(
                                text = { Text("${shutter.name} (WHERE ${shutter.where})") },
                                onClick = {
                                    repository.assignObserved(entry.where, shutter.id)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { repository.addShutterFromObserved(entry.where) }) {
                    Text(stringResource(R.string.settings_add_this))
                }
            }
        }
    }
}

// --------------------------------------------------------------- volet editeur

/** Nom, piece, adresse bus et temps de course d'un volet. */
@Composable
private fun ShutterEditorCard(repository: VoletsRepository, shutter: Shutter) {
    var name by remember(shutter.id) { mutableStateOf(shutter.name) }
    var room by remember(shutter.id) { mutableStateOf(shutter.room) }
    var where by remember(shutter.id) { mutableStateOf(shutter.where.toString()) }
    var travel by remember(shutter.id) {
        mutableStateOf((shutter.travelMs / 1000).coerceAtLeast(0).toString())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = shutter.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                label = { Text(stringResource(R.string.settings_room)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = where,
                onValueChange = { value -> where = value.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.settings_where)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = travel,
                onValueChange = { value -> travel = value.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.settings_travel_s)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        repository.rename(shutter.id, name, room)
                        repository.setWhere(shutter.id, where.toIntOrNull() ?: shutter.where)
                        repository.setTravel(shutter.id, (travel.toLongOrNull() ?: 0L) * 1000)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_save)) }

                TextButton(
                    onClick = { repository.deleteShutter(shutter.id) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.settings_delete)) }
            }
        }
    }
}

/** Titre de la partie volets + remise aux valeurs par defaut. */
@Composable
private fun VoletsHeaderCard(repository: VoletsRepository) {
    SettingsCard(title = stringResource(R.string.settings_volets)) {
        Text(
            text = stringResource(R.string.settings_volets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = repository::resetDefaults, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_reset_defaults))
        }
    }
}

/** Creer un volet quand une adresse est vue sur le bus mais n'existe pas encore. */
@Composable
private fun AddShutterCard(repository: VoletsRepository) {
    var name by remember { mutableStateOf("") }
    var where by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(repository.currentSettings().defaultModel) }

    SettingsCard(title = stringResource(R.string.settings_add_volet)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = where,
            onValueChange = { value -> where = value.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.settings_where)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ModelPicker(selected = model, onSelect = { model = it })
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val w = where.toIntOrNull() ?: 0
                repository.addShutter(
                    Shutter(
                        id = "volet_" + System.currentTimeMillis(),
                        name = name.trim().ifBlank { "Volet $w" },
                        room = model.label(),
                        where = w,
                        model = model,
                        travelMs = repository.currentSettings().defaultTravelMs,
                    )
                )
                name = ""
                where = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_add_volet)) }
    }
}

// -------------------------------------------------------------------- journal

/** Messages de diagnostic + dernieres trames echangees. */
@Composable
private fun LogCard(repository: VoletsRepository) {
    val notices by repository.notices.collectAsState()
    val log by repository.log.collectAsState()

    SettingsCard(title = stringResource(R.string.settings_log)) {
        notices.take(6).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (log.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_log_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            log.take(60).forEach { entry ->
                Text(
                    text = "${entry.time}  ${entry.dir}  ${entry.raw}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------- petite mise en page

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
