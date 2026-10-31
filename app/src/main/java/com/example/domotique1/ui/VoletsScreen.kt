package com.example.domotique1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domotique1.R
import kotlin.math.roundToInt
import com.example.domotique1.data.Shutter
import com.example.domotique1.data.ShutterState
import com.example.domotique1.data.UiStatus
import com.example.domotique1.data.VoletsRepository
import com.example.domotique1.data.movementLabel
import com.example.domotique1.own.GatewayStatus
import com.example.domotique1.own.OwnMovement
import com.example.domotique1.own.OpenWebNet
import kotlinx.coroutines.delay

/**
 * Ecran principal : les 9 volets, avec pour chacun MONTER / STOP / DESCENDRE
 * et une barre de position estimee.
 *
 * Toutes les donnees viennent de [VoletsRepository] (StateFlow) : elles restent
 * justes quand on tourne l'ecran ou quand on va dans les reglages.
 */
@Composable
fun VoletsScreen(repository: VoletsRepository, onSettings: () -> Unit) {
    val volets by repository.volets.collectAsState()
    val states by repository.states.collectAsState()
    val busy by repository.busy.collectAsState()
    val status by repository.status.collectAsState()
    val notices by repository.notices.collectAsState()

    /** carte dont on a demande la position a viser (-1 : aucune) */
    var positionEditor by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------ entete + connexion
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            ConnectionPill(status)
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onSettings) {
                Text(stringResource(R.string.settings_title))
            }
        }
        Text(
            text = status.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))

        // --------------------------------- derniere trame partiel / reponse recue
        LastExchangeBand(status.gateway)

        Spacer(Modifier.height(8.dp))

        // --------------------------------------------------- commandes groupe
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = repository::allUp, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.main_all_up))
            }
            OutlinedButton(onClick = repository::allDown, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.main_all_down))
            }
            Button(onClick = repository::allStop, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.main_all_stop))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------------------ volets
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(volets, key = { it.id }) { shutter ->
                VoletCard(
                    shutter = shutter,
                    state = states[shutter.id] ?: ShutterState.IDLE,
                    sending = shutter.id in busy,
                    showPosition = positionEditor == shutter.id,
                    onTogglePosition = {
                        positionEditor = if (positionEditor == shutter.id) null else shutter.id
                    },
                    onMove = { action -> repository.sendMovement(shutter.id, action) },
                    onPercent = { target -> repository.setPercent(shutter.id, target) },
                )
            }

            item {
                Text(
                    text = notices.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

/** Puce "connecte / deconnecte" de l'entete. */
/**
 * Bandeau du haut : la derniere trame ecrite sur le port 20000 et ce que la
 * passerelle a renvoye en face.
 *
 * Cette passerelle ne repond pas dans la seconde : elle garde les trames puis
 * renvoie ses rapports en rafale, parfois plusieurs minutes apres. Le bandeau
 * affiche donc "en attente de reponse" tant qu'aucune reponse n'est arrivee,
 * avec l'heure exacte de l'envoi et de la reponse pour pouvoir comparer.
 */
@Composable
private fun LastExchangeBand(gateway: GatewayStatus) {
    // rafraichit les "il y a X s" sans dependre d'une trame recue
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.main_last_exchange),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))

            if (gateway.lastSentFrame.isEmpty()) {
                Text(
                    text = stringResource(R.string.main_no_command),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ">>  " + gateway.lastSentFrame,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = agoText(now, gateway.lastSentAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (gateway.lastSentLabel.isNotEmpty()) {
                    Text(
                        text = gateway.lastSentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            if (gateway.lastAnswerFrame.isEmpty()) {
                Text(
                    text = stringResource(
                        if (gateway.lastSentFrame.isEmpty()) R.string.main_no_answer
                        else R.string.main_waiting_answer
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val notReply = gateway.lastAnswerIsNotReply
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "<<  " + gateway.lastAnswerFrame,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            gateway.lastAnswerIsNack -> Color(0xFFD32F2F)
                            notReply -> Color(0xFF616161)
                            else -> Color(0xFF2E7D32)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = agoText(now, gateway.lastAnswerAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = buildString {
                        append(stringResource(answerTextFor(gateway)))
                        // pas de latence tant que la reponse n'a pas ete reellement associee
                        // a la commande : un echo de keepalive ou une trame spontanee de la
                        // passerelle donnerait un ecart de plusieurs minutes sans signification
                        if (!notReply) {
                            gateway.answerLatencyMs.takeIf { it >= 0 }?.let {
                                append("  (")
                                append(latencyText(it))
                                append(")")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "il y a 12 s", vide si l'evenement ne s'est jamais produit. */
@Composable
private fun agoText(now: Long, at: Long): String =
    if (at <= 0) "" else stringResource(R.string.main_ago, durationText((now - at) / 1000))

/** Libelle de la derniere trame recue : reponse, echo du maintien, ou trame spontanee. */
@Composable
private fun answerTextFor(gateway: GatewayStatus): Int = when {
    gateway.lastAnswerIsNack -> R.string.main_answer_nack
    gateway.lastAnswerIsNotReply ->
        if (gateway.lastAnswerFrame == OpenWebNet.FRAME_ACK) R.string.main_answer_keepalive
        else R.string.main_answer_unsolicited
    else -> R.string.main_answer_ok
}

private fun durationText(totalSeconds: Long): String {
    if (totalSeconds < 60) return "$totalSeconds s"
    val minutes = totalSeconds / 60
    if (minutes < 60) {
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        return "$minutes min $seconds s"
    }
    val hours = minutes / 60
    return "$hours h ${(minutes % 60).toString().padStart(2, '0')} min"
}

private fun latencyText(ms: Long): String =
    if (ms < 1000) "$ms ms" else "${(ms / 100.0).roundToInt() / 10.0} s"

@Composable
private fun ConnectionPill(status: UiStatus) {
    val color = if (status.connected) Color(0xFF2E7D32) else Color(0xFFB26A00)
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (status.connected) R.string.volet_connected else R.string.status_disconnected
                ),
                fontSize = 12.sp,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Une carte par volet : nom, piece, adresse bus, etat, barre de position et
 * les trois commandes. Le chevron de la barre de position ouvre le curseur
 * qui permet de viser un pourcentage.
 */
@Composable
private fun VoletCard(
    shutter: Shutter,
    state: ShutterState,
    sending: Boolean,
    showPosition: Boolean,
    onTogglePosition: () -> Unit,
    onMove: (OwnMovement) -> Unit,
    onPercent: (Int) -> Unit,
) {
    val accent = colorFor(state.action, state.isMoving)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            // --------------------------------------------------- titre carte
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = shutter.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = shutterInfo(shutter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = movementLabel(state.action),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------- barre position
            PositionBar(
                percent = state.positionPercent,
                moving = state.isMoving,
                color = accent,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.volet_position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onTogglePosition) {
                    Text(
                        text = if (showPosition) "-" else "+",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (showPosition) {
                var target by remember(shutter.id) {
                    mutableFloatStateOf(state.currentPercent().toFloat())
                }
                Text(
                    text = stringResource(R.string.main_target, target.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChangeFinished = { onPercent(target.roundToInt()) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onPercent(0) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.volet_move_up))
                    }
                    OutlinedButton(onClick = { onPercent(50) }, modifier = Modifier.weight(1f)) {
                        Text("50 %")
                    }
                    OutlinedButton(onClick = { onPercent(100) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.volet_move_down))
                    }
                }
            }

            // ---------------------------------------------------- commandes
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpDownButton(
                    label = stringResource(R.string.volet_move_up),
                    enabled = shutter.configured,
                    onClick = { onMove(OwnMovement.UP) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onMove(OwnMovement.STOP) },
                    enabled = shutter.configured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.volet_move_stop))
                }
                UpDownButton(
                    label = stringResource(R.string.volet_move_down),
                    enabled = shutter.configured,
                    onClick = { onMove(OwnMovement.DOWN) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (!shutter.configured) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.volet_not_configured),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun UpDownButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Barre de position : 0 % = volet ouvert (remonte), 100 % = volet ferme (descendu).
 * Barre pointillee quand la position n'est pas connue, hachuree quand le volet bouge.
 */
@Composable
private fun PositionBar(percent: Int, moving: Boolean, color: Color) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (percent < 0) {
                Text(
                    text = stringResource(R.string.volet_unknown),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                        .fillMaxSize()
                        .background(if (moving) color.copy(alpha = 0.45f) else color)
                )
                Text(
                    text = "$percent %",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                )
            }
        }
    }
}

/** couleur de l'etat : en mouvement = orange, sinon couleur du dernier ordre recu. */
private fun colorFor(action: OwnMovement, moving: Boolean): Color = when {
    moving -> Color(0xFFB26A00)
    action == OwnMovement.UP -> Color(0xFF2E7D32)
    action == OwnMovement.DOWN -> Color(0xFF1565C0)
    else -> Color(0xFF6B7280)
}

/** ligne d'information sous le nom : piece + adresse bus + temps de course. */
private fun shutterInfo(shutter: Shutter): String = buildString {
    append(shutter.room)
    append("  -  ")
    if (shutter.configured) {
        append("WHERE ")
        append(shutter.where)
    } else {
        append("WHERE ?")
    }
    append("  -  ")
    append(shutter.effectiveTravelMs() / 1000)
    append(" s")
}
