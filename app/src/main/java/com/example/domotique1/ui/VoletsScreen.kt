package com.example.domotique1.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domotique1.R
import com.example.domotique1.ui.theme.InkInner
import com.example.domotique1.ui.theme.InkNight
import com.example.domotique1.ui.theme.InkPanel
import com.example.domotique1.ui.theme.NeonAmber
import com.example.domotique1.ui.theme.NeonCyan
import com.example.domotique1.ui.theme.NeonRed
import com.example.domotique1.ui.theme.NeonTurquoise
import com.example.domotique1.ui.theme.PaperDim
import com.example.domotique1.ui.theme.PaperFaint
import com.example.domotique1.ui.theme.PaperWhite
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
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

        Spacer(Modifier.height(8.dp))

        // --------------------------------- derniere trame partiel / reponse recue
        LastExchangeBand(status.gateway)
    }
}

/** Puce "connecte / deconnecte" de l'entete, facon console : pastille + lueur. */
@Composable
private fun ConnectionPill(status: UiStatus) {
    val color = if (status.connected) NeonTurquoise else NeonAmber
    val shape = CircleShape
    Box(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
 * Bandeau du bas : la derniere trame ecrite sur le port 20000 et ce que la
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

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(InkPanel.copy(alpha = 0.85f))
            .border(BorderStroke(1.dp, NeonCyan.copy(alpha = 0.25f)), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.main_last_exchange),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = PaperDim,
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
                        color = PaperWhite,
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
            HorizontalDivider(color = PaperFaint.copy(alpha = 0.25f))
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
                            gateway.lastAnswerIsNack -> NeonRed
                            notReply -> PaperFaint
                            else -> NeonTurquoise
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

/**
 * Carte d'un volet, facon pupitre de commande : fond decale en gradient,
 * bordure et lueur couleur etat, grande position en chiffres monospace,
 * touches MONTER / STOP / DESCENDRE.
 *
 * Le bouton POSITION ouvre le curseur qui permet de viser un pourcentage.
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
    val glow = state.isMoving || sending
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neonShadow(14.dp, accent, enabled = glow)
            .clip(shape)
            .background(Brush.linearGradient(listOf(InkPanel, InkInner)))
            .border(BorderStroke(1.dp, accent.copy(alpha = if (glow) 0.9f else 0.35f)), shape)
            .padding(14.dp),
    ) {
        Column {
            // --------------------------------------------------- titre carte
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = shutter.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = PaperWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shutterInfo(shutter),
                        style = MaterialTheme.typography.labelSmall,
                        color = PaperFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // puce d'etat : mouvement en cours, avec spinner pendant l'envoi
                val chipShape = CircleShape
                Box(
                    modifier = Modifier
                        .clip(chipShape)
                        .background(accent.copy(alpha = 0.12f))
                        .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), chipShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = accent,
                            )
                        }
                        Text(
                            text = movementLabel(state.action),
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------- jauge + position visee
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.volet_position).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = PaperFaint,
                        letterSpacing = 1.5.sp,
                    )
                    ShutterGauge(percent = state.positionPercent, moving = state.isMoving, color = accent)
                }
                PositionDigits(percent = state.positionPercent, color = accent)
            }

            // ------------------------------------------------- curseur position
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onTogglePosition) {
                    Text(
                        text = (if (showPosition) "- " else "+ ") +
                            stringResource(R.string.volet_position).uppercase(),
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = if (showPosition) accent else PaperDim,
                    )
                }
            }

            if (showPosition) {
                var target by remember(shutter.id) {
                    // position inconnue (-1) : on part de 0 pour rester dans la plage du curseur
                    mutableFloatStateOf(state.currentPercent().coerceAtLeast(0).toFloat())
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
                NeonKeyButton(
                    glyph = "▲",
                    label = stringResource(R.string.volet_move_up),
                    accent = accent,
                    sending = sending,
                    enabled = shutter.configured,
                    onClick = { onMove(OwnMovement.UP) },
                    modifier = Modifier.weight(1f),
                )
                NeonKeyButton(
                    glyph = "■",
                    label = stringResource(R.string.volet_move_stop),
                    accent = accent,
                    sending = sending,
                    enabled = shutter.configured,
                    filled = true,
                    onClick = { onMove(OwnMovement.STOP) },
                    modifier = Modifier.weight(1f),
                )
                NeonKeyButton(
                    glyph = "▼",
                    label = stringResource(R.string.volet_move_down),
                    accent = accent,
                    sending = sending,
                    enabled = shutter.configured,
                    onClick = { onMove(OwnMovement.DOWN) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (!shutter.configured) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.volet_not_configured),
                    color = NeonRed,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Jauge fine de position : 0 % = volet ouvert (remonte), 100 % = volet ferme
 * (descendu). Barre vide balisee "?" quand la position n'est pas connue,
 * trait lumineux plus pale quand le volet bouge.
 */
@Composable
private fun ShutterGauge(percent: Int, moving: Boolean, color: Color) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), shape),
    ) {
        if (percent >= 0) {
            Box(
                Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .background(if (moving) color.copy(alpha = 0.45f) else color)
            )
        }
    }
}

/** Position du volet en grands chiffres monospace, "--" si elle est inconnue. */
@Composable
private fun PositionDigits(percent: Int, color: Color) {
    Text(
        text = if (percent < 0) "--" else "$percent",
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = if (percent < 0) PaperFaint else color,
        modifier = Modifier.neonShadow(6.dp, color, enabled = percent >= 0),
    )
}

/** Touche de commande facon pupitre : glyph + libelle, lueur quand elle est armee. */
@Composable
private fun NeonKeyButton(
    glyph: String,
    label: String,
    accent: Color,
    sending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val textColor = when {
        !enabled -> PaperFaint
        filled -> InkNight
        else -> accent
    }
    Box(
        modifier = modifier
            .neonShadow(8.dp, accent, enabled = enabled && filled)
            .clip(shape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.04f)
                    filled -> accent
                    else -> accent.copy(alpha = 0.08f)
                }
            )
            .border(BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.5f else 0.15f)), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = textColor)
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(glyph, fontSize = 13.sp, color = textColor)
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = textColor,
                )
            }
        }
    }
}

/** Ombre coloree facon neon : disparait completement quand [enabled] est faux. */
private fun Modifier.neonShadow(elevation: Dp, color: Color, enabled: Boolean): Modifier =
    if (!enabled) this else shadow(elevation, ambientColor = color, spotColor = color)

/** couleur de l'etat : en mouvement = ambre, sinon teinte du dernier ordre recu. */
private fun colorFor(action: OwnMovement, moving: Boolean): Color = when {
    moving -> NeonAmber
    action == OwnMovement.UP -> NeonTurquoise
    action == OwnMovement.DOWN -> NeonCyan
    else -> PaperFaint
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
