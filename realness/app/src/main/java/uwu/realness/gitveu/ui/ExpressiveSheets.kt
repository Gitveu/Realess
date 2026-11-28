package uwu.realness.gitveu.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uwu.realness.gitveu.R
import uwu.realness.gitveu.audio.EqTransferCodec
import uwu.realness.gitveu.audio.FindBudsChannel
import uwu.realness.gitveu.protocol.BudsProtocol
import uwu.realness.gitveu.update.AppReleaseInfo
import uwu.realness.gitveu.update.AppUpdater
import uwu.realness.gitveu.update.UpdateDownloadState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive Material 3 Expressive Custom Equalizer Editor (ModalBottomSheet).
 * Renders a smooth cubic-Bezier frequency curve with draggable points adapted to
 * the active headset protocol's band count and dB range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEqEditorSheet(
    spec: BudsProtocol.CustomEqSpec,
    initialGains: List<Int>,
    editingProfile: BudsProtocol.CustomEqProfile?,
    isCreatingNew: Boolean,
    onDismiss: () -> Unit,
    onApply: (gains: List<Int>, profileName: String?) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bandCount = spec.bandLabels.size
    val gains = remember(spec, editingProfile, isCreatingNew) {
        mutableStateListOf<Int>().apply {
            for (i in 0 until bandCount) {
                val init = if (isCreatingNew) 0 else (editingProfile?.gains?.getOrElse(i) { 0 }
                    ?: initialGains.getOrElse(i) { 0 })
                add(init.coerceIn(spec.minDb, spec.maxDb))
            }
        }
    }
    var profileName by remember(editingProfile, isCreatingNew) {
        mutableStateOf(
            if (isCreatingNew) "" else (editingProfile?.name ?: "")
        )
    }
    var showImportCodeDialog by remember { mutableStateOf(false) }

    fun applyImportedEq(rawText: String, fallbackName: String? = null) {
        val imported = EqTransferCodec.importFromText(rawText, spec, fallbackName)
        if (imported == null) {
            Toast.makeText(context, context.getString(R.string.eq_import_error), Toast.LENGTH_SHORT).show()
            return
        }
        for (i in 0 until bandCount) {
            gains[i] = imported.gains.getOrElse(i) { 0 }.coerceIn(spec.minDb, spec.maxDb)
        }
        if (spec.supportsMultipleProfiles && profileName.isBlank() && !imported.profileName.isNullOrBlank()) {
            profileName = imported.profileName.take(20)
        }
        Toast.makeText(
            context,
            context.getString(R.string.eq_import_success, imported.sourceFormat, imported.sourcePointCount, bandCount),
            Toast.LENGTH_SHORT
        ).show()
    }

    val autoEqFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                val baseName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.replace('_', ' ')
                    ?.take(20)
                applyImportedEq(content, fallbackName = baseName)
            } else {
                Toast.makeText(context, context.getString(R.string.eq_import_error), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            Toast.makeText(context, context.getString(R.string.eq_import_error), Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCreatingNew) {
                            stringResource(R.string.eq_custom_new_profile)
                        } else {
                            stringResource(R.string.eq_custom_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.eq_custom_subtitle, bandCount, spec.minDb, spec.maxDb),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = {
                        for (i in 0 until bandCount) gains[i] = 0
                        onReset()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.reset))
                }
            }

            // Profile name input for protocols that store named custom curves (realme / Huawei)
            if (spec.supportsMultipleProfiles) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { if (it.length <= 20) profileName = it },
                    label = { Text(stringResource(R.string.eq_custom_profile_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Interactive Spline EQ Graph Card
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EqCurveInteractiveGraph(
                        bandLabels = spec.bandLabels,
                        gains = gains,
                        minDb = spec.minDb,
                        maxDb = spec.maxDb,
                        onBandChanged = { idx, value ->
                            if (idx in gains.indices) {
                                gains[idx] = value.coerceIn(spec.minDb, spec.maxDb)
                            }
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.eq_custom_low_freq),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.eq_custom_high_freq),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.eq_custom_drag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Share code / Import code / AutoEQ & Wavelet (.txt) toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        val code = EqTransferCodec.encodeShareCode(spec, gains.toList(), profileName.trim().ifEmpty { null })
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, context.getString(R.string.eq_share_copied), Toast.LENGTH_SHORT).show()
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.eq_btn_share_code),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                FilledTonalButton(
                    onClick = { showImportCodeDialog = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.ContentPasteGo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.eq_btn_import_code),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                FilledTonalButton(
                    onClick = {
                        try {
                            autoEqFileLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
                        } catch (_: Throwable) {
                            Toast.makeText(context, context.getString(R.string.eq_import_error), Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.eq_btn_import_file),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onApply(gains.toList(), profileName.trim().ifEmpty { null }) },
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (spec.supportsMultipleProfiles) {
                            stringResource(R.string.save)
                        } else {
                            stringResource(R.string.apply)
                        }
                    )
                }
            }
        }
    }

    if (showImportCodeDialog) {
        EqImportCodeDialog(
            onImport = { raw ->
                showImportCodeDialog = false
                applyImportedEq(raw)
            },
            onDismiss = { showImportCodeDialog = false }
        )
    }
}

@Composable
private fun EqImportCodeDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.eq_import_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.eq_import_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("RNQ1:... / GraphicEQ:...") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        clipboard.getText()?.text?.let { clip ->
                            if (clip.isNotBlank()) text = clip
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.eq_import_paste_clipboard))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
                shape = CircleShape
            ) {
                Text(stringResource(R.string.eq_btn_import_code))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
private fun EqCurveInteractiveGraph(
    bandLabels: List<String>,
    gains: List<Int>,
    minDb: Int,
    maxDb: Int,
    onBandChanged: (index: Int, value: Int) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val bandCount = bandLabels.size.coerceAtLeast(1)
    var activeBand by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Signed dB readouts row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0 until bandCount) {
                val g = gains.getOrElse(i) { 0 }
                val text = if (g > 0) "+$g" else "$g"
                val isSelected = activeBand == i
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = if (isSelected || g != 0) FontWeight.Bold else FontWeight.Medium,
                        fontSize = if (bandCount >= 8) 10.sp else 12.sp
                    ),
                    color = if (isSelected || g != 0) primaryColor else labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Y-axis + Interactive Canvas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(212.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "+${maxDb}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontSize = 11.sp),
                    color = labelColor
                )
                Text(
                    text = "0 dB",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontSize = 10.sp),
                    color = labelColor
                )
                Text(
                    text = "$minDb",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontSize = 11.sp),
                    color = labelColor
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(bandCount, minDb, maxDb) {
                        fun updateAt(pos: Offset, lockBand: Int? = null): Int {
                            val padX = size.width / (bandCount * 2f)
                            val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                            val padY = 16.dp.toPx()
                            val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

                            val idx = lockBand ?: if (bandCount == 1) {
                                0
                            } else {
                                (((pos.x - padX) / usableW) * (bandCount - 1))
                                    .roundToInt()
                                    .coerceIn(0, bandCount - 1)
                            }
                            val ratio = (1f - ((pos.y - padY) / usableH)).coerceIn(0f, 1f)
                            val db = (minDb + ratio * (maxDb - minDb)).roundToInt().coerceIn(minDb, maxDb)
                            onBandChanged(idx, db)
                            return idx
                        }

                        detectTapGestures { offset ->
                            activeBand = updateAt(offset)
                        }
                    }
                    .pointerInput(bandCount, minDb, maxDb) {
                        var draggingIdx: Int? = null
                        fun updateAt(pos: Offset, lockBand: Int? = null): Int {
                            val padX = size.width / (bandCount * 2f)
                            val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                            val padY = 16.dp.toPx()
                            val usableH = (size.height - padY * 2f).coerceAtLeast(1f)

                            val idx = lockBand ?: if (bandCount == 1) {
                                0
                            } else {
                                (((pos.x - padX) / usableW) * (bandCount - 1))
                                    .roundToInt()
                                    .coerceIn(0, bandCount - 1)
                            }
                            val ratio = (1f - ((pos.y - padY) / usableH)).coerceIn(0f, 1f)
                            val db = (minDb + ratio * (maxDb - minDb)).roundToInt().coerceIn(minDb, maxDb)
                            onBandChanged(idx, db)
                            return idx
                        }

                        detectDragGestures(
                            onDragStart = { start ->
                                val idx = updateAt(start)
                                draggingIdx = idx
                                activeBand = idx
                            },
                            onDragEnd = {
                                draggingIdx = null
                                activeBand = null
                            },
                            onDragCancel = {
                                draggingIdx = null
                                activeBand = null
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                // Allow gliding smoothly across adjacent bands if finger moves horizontally
                                val padX = size.width / (bandCount * 2f)
                                val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                                val nearest = if (bandCount == 1) 0 else {
                                    (((change.position.x - padX) / usableW) * (bandCount - 1))
                                        .roundToInt()
                                        .coerceIn(0, bandCount - 1)
                                }
                                val current = draggingIdx ?: nearest
                                val chosen = if (abs(nearest - current) >= 1) nearest else current
                                draggingIdx = chosen
                                activeBand = chosen
                                updateAt(change.position, chosen)
                            }
                        )
                    }
            ) {
                val padX = size.width / (bandCount * 2f)
                val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                val padY = 16.dp.toPx()
                val usableH = (size.height - padY * 2f).coerceAtLeast(1f)
                val span = (maxDb - minDb).coerceAtLeast(1).toFloat()

                fun xFor(i: Int): Float = if (bandCount == 1) size.width / 2f else padX + usableW * i / (bandCount - 1)
                fun yFor(db: Int): Float = padY + usableH * (1f - ((db - minDb) / span))

                val zeroY = yFor(0)

                // Subtle horizontal zero-line
                drawLine(
                    color = outlineColor.copy(alpha = 0.6f),
                    start = Offset(padX, zeroY),
                    end = Offset(size.width - padX, zeroY),
                    strokeWidth = 1.dp.toPx()
                )

                // Vertical band tracks
                val points = ArrayList<Offset>(bandCount)
                for (i in 0 until bandCount) {
                    val x = xFor(i)
                    val y = yFor(gains.getOrElse(i) { 0 })
                    points.add(Offset(x, y))
                    drawLine(
                        color = outlineColor.copy(alpha = 0.45f),
                        start = Offset(x, padY),
                        end = Offset(x, size.height - padY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Smooth cubic Bezier spline + soft tonal fill underneath
                if (points.size >= 2) {
                    val linePath = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        for (i in 0 until points.lastIndex) {
                            val p0 = points[i]
                            val p1 = points[i + 1]
                            val midX = (p0.x + p1.x) / 2f
                            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(points.last().x, size.height - padY)
                        lineTo(points.first().x, size.height - padY)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.26f),
                                primaryColor.copy(alpha = 0.02f)
                            ),
                            startY = padY,
                            endY = size.height - padY
                        )
                    )

                    drawPath(
                        path = linePath,
                        color = primaryColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Draggable circular handles
                val baseRadius = if (bandCount >= 8) 6.dp.toPx() else 8.dp.toPx()
                for (i in points.indices) {
                    val pt = points[i]
                    val isDragging = activeBand == i
                    if (isDragging) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.22f),
                            radius = baseRadius * 2.1f,
                            center = pt
                        )
                    }
                    drawCircle(
                        color = surfaceColor,
                        radius = baseRadius,
                        center = pt
                    )
                    drawCircle(
                        color = if (isDragging) onSurfaceColor else primaryColor,
                        radius = baseRadius,
                        center = pt,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }
        }

        // Band frequency labels row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0 until bandCount) {
                Text(
                    text = bandLabels[i],
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontSize = if (bandCount >= 8) 9.sp else 11.sp
                    ),
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Universal Find My Earbuds ModalBottomSheet (works on ALL headsets via escalating AudioTrack
 * stereo tone, plus optional built-in firmware chime where supported).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindBudsSheet(
    isPlaying: Boolean,
    volumeProgress: Float,
    selectedChannel: FindBudsChannel,
    supportsHardwareRing: Boolean,
    hardwareRingActive: Boolean,
    isConnected: Boolean,
    onChannelSelected: (FindBudsChannel) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleHardwareRing: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.find_buds_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.find_buds_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ear-safety notice
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Hearing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.find_buds_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Stereo channel picker (Left / Both / Right)
            val channels = listOf(
                FindBudsChannel.LEFT to R.string.find_buds_channel_left,
                FindBudsChannel.BOTH to R.string.find_buds_channel_both,
                FindBudsChannel.RIGHT to R.string.find_buds_channel_right
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                channels.forEachIndexed { index, (ch, labelRes) ->
                    SegmentedButton(
                        selected = selectedChannel == ch,
                        onClick = { onChannelSelected(ch) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = channels.size)
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Escalating volume ramp progress indicator
            if (isPlaying) {
                val pct = (volumeProgress * 100f).roundToInt().coerceIn(0, 100)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.find_buds_volume_level, pct),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.find_buds_playing_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { volumeProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )
                }
            }

            // Optional native firmware ring toggle (realme / Nothing / Galaxy Buds)
            if (supportsHardwareRing && isConnected) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.find_buds_hardware_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.find_buds_hardware_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = hardwareRingActive,
                            onCheckedChange = onToggleHardwareRing
                        )
                    }
                }
            }

            // Main expressive Play / Stop pill button
            Button(
                onClick = onTogglePlay,
                shape = CircleShape,
                colors = if (isPlaying) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(if (isPlaying) R.string.find_buds_stop else R.string.find_buds_start),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Material 3 Expressive Rename Earbuds Dialog.
 */
@Composable
fun RenameDeviceDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.rename_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 31) text = it },
                    label = { Text(stringResource(R.string.rename_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.rename_reset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                shape = CircleShape
            ) {
                Text(stringResource(R.string.rename_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * In-app GitHub Releases updater dialog inspired by keqdroid:
 * displays version transition, Pre-release badge, APK size, release notes, live download
 * progress bar, FileProvider APK installer launch, and "Skip version" action.
 */
@Composable
fun UpdateReleaseDialog(
    release: AppReleaseInfo,
    onOpenInBrowser: (String) -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadState by AppUpdater.downloadState.collectAsState()

    AlertDialog(
        onDismissRequest = {
            if (downloadState !is UpdateDownloadState.Downloading) onDismiss()
        },
        icon = {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.update_dialog_title, release.tagName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v${release.currentVersion} → ${release.tagName}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (release.formattedSize.isNotEmpty()) {
                        Text(
                            text = "· ${release.formattedSize}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (release.isPrerelease) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = "Pre-release",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (release.releaseNotes.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        Text(
                            text = release.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }

                when (val dl = downloadState) {
                    is UpdateDownloadState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val pct = (dl.progress * 100f).roundToInt()
                            Text(
                                text = stringResource(R.string.update_downloading_pct, pct),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (dl.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { dl.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape)
                                )
                            }
                        }
                    }
                    is UpdateDownloadState.ReadyToInstall -> {
                        Text(
                            text = stringResource(R.string.update_ready_install),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is UpdateDownloadState.Error -> {
                        Text(
                            text = stringResource(R.string.update_error_fmt, dl.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    UpdateDownloadState.Idle -> {}
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { onOpenInBrowser(release.htmlUrl) },
                        enabled = downloadState !is UpdateDownloadState.Downloading
                    ) {
                        Text(stringResource(R.string.update_open_github))
                    }
                    TextButton(
                        onClick = onSkipVersion,
                        enabled = downloadState !is UpdateDownloadState.Downloading
                    ) {
                        Text(stringResource(R.string.update_skip_version))
                    }
                }
            }
        },
        confirmButton = {
            val isDownloading = downloadState is UpdateDownloadState.Downloading
            val readyFile = (downloadState as? UpdateDownloadState.ReadyToInstall)?.apkFile
            Button(
                enabled = !isDownloading,
                shape = CircleShape,
                onClick = {
                    if (readyFile != null && readyFile.exists()) {
                        if (!AppUpdater.canRequestPackageInstalls(context)) {
                            Toast.makeText(context, context.getString(R.string.update_install_perm_toast), Toast.LENGTH_LONG).show()
                            AppUpdater.openInstallPermissionSettings(context)
                        } else {
                            AppUpdater.launchApkInstaller(context, readyFile)
                        }
                    } else {
                        scope.launch {
                            val file = AppUpdater.downloadUpdate(context, release)
                            if (file != null) {
                                if (!AppUpdater.canRequestPackageInstalls(context)) {
                                    Toast.makeText(context, context.getString(R.string.update_install_perm_toast), Toast.LENGTH_LONG).show()
                                    AppUpdater.openInstallPermissionSettings(context)
                                } else {
                                    AppUpdater.launchApkInstaller(context, file)
                                }
                            }
                        }
                    }
                }
            ) {
                Text(
                    text = if (readyFile != null) {
                        stringResource(R.string.update_install_now)
                    } else {
                        stringResource(R.string.update_download_install)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = downloadState !is UpdateDownloadState.Downloading
            ) {
                Text(stringResource(R.string.update_later))
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}
