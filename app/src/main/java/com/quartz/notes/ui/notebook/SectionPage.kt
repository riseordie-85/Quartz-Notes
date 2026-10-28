package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import com.quartz.notes.ui.media.model
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.quartz.notes.ui.components.QuartzSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.ui.components.ColorPickerDialog
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.media.rememberImageAdder
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/** Bold colors for section pages, so they stand out from ordinary pages (and color their tabs). */
val SectionColorPresets: List<Color> = listOf(
    Color(0xFFF9C78A), // apricot
    Color(0xFFFF9F43), // orange
    Color(0xFFE8505B), // coral
    Color(0xFFD6006A), // raspberry
    Color(0xFFB57EDC), // lavender
    Color(0xFF7C4DCC), // purple
    Color(0xFF6CCFF6), // sky
    Color(0xFF2E86DE), // blue
    Color(0xFF1ABC9C), // teal
    Color(0xFF2ECC71), // green
    Color(0xFFFFD43B), // yellow
    Color(0xFF34495E), // slate
)

/** Color for a new section: the next bold color after the ones already used. */
fun nextSectionColor(existingSections: Int): Int = SectionColorPresets[existingSections % SectionColorPresets.size].toArgb()

/** Default color of a section page whose color was cleared. */
private val DefaultSectionColor = SectionColorPresets.first()

/** A section page's color (also its tab's color). */
val PageEntity.sectionColor: Color get() = backgroundArgb?.let { Color(it) } ?: DefaultSectionColor

/**
 * The opening page of a section: a big centered title, a short description on ruled lines and an
 * icon or photo, on a bold color.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun SectionPageView(
    page: PageEntity,
    session: PageSession,
    vm: NotebookViewModel,
    side: PageSide,
    onActivate: () -> Unit,
    onStyle: () -> Unit,
) {
    val bg = page.sectionColor
    val ink = contentColorFor(bg)
    val accent = MaterialTheme.colorScheme.primary
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val media = LocalAppContainer.current.media
    val latestPage by rememberUpdatedState(page)
    val seamShade = if (LocalQuartzColors.current.isDark) 0.45f else 0.13f

    // Typing is saved shortly after it stops, and when the page goes away.
    LaunchedEffect(session) {
        snapshotFlow { session.title to session.description }.drop(1).debounce(600).collect { vm.saveSection(latestPage) }
    }
    DisposableEffect(session) {
        onDispose { vm.saveSectionIfChanged(latestPage) }
    }

    // Sharing: words, or a picture of the page.
    val capture = remember { PageCapture() }
    DisposableEffect(session) {
        val exporter = PageExporter(
            text = { listOf(session.title.trim(), session.description.trim()).filter { it.isNotEmpty() }.joinToString("\n\n") },
            image = { capture.toPng(context, session.title) { focusManager.clearFocus() } },
        )
        session.exporter = exporter
        onDispose { if (session.exporter === exporter) session.exporter = null }
    }
    val exporting = capture.exporting

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            .onFocusChanged { if (it.hasFocus) onActivate() }
            .shadow(2.dp, side.shape)
            .clip(side.shape)
            .background(bg)
            .drawBehind {
                when (side) {
                    PageSide.LEFT, PageSide.RIGHT -> drawSeam(atRightEdge = side == PageSide.LEFT, strength = seamShade)
                    PageSide.SPREAD -> drawCenterSeam(seamShade)
                    PageSide.SINGLE -> Unit
                }
            },
    ) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewport)
                    .then(capture.modifier(background = { bg }, texture = { null }))
                    .padding(horizontal = 32.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val titleStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center)
                BasicTextField(
                    value = session.title,
                    onValueChange = { session.title = it.replace("\n", "") },
                    textStyle = titleStyle,
                    cursorBrush = SolidColor(ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (session.title.isEmpty() && !exporting) {
                                Text("Section title", style = titleStyle.copy(color = ink.copy(alpha = 0.4f)))
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(24.dp))
                DescriptionField(
                    value = session.description,
                    onChange = { session.description = it },
                    ink = ink,
                    showPlaceholder = !exporting,
                )
                Spacer(Modifier.height(36.dp))

                // Icon or photo; tap it to change.
                val image = page.sectionImage
                val icon = NotebookIcons.of(page.sectionIcon ?: "none")
                when {
                    image != null -> AsyncImage(
                        model = media.model(image),
                        contentDescription = "Section photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(180.dp)
                            .shadow(6.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onStyle),
                    )
                    icon != null -> Icon(
                        icon,
                        contentDescription = "Section icon",
                        tint = ink,
                        modifier = Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onStyle)
                            .padding(8.dp),
                    )
                    !exporting -> Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.5.dp, ink.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .clickable(onClick = onStyle)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text("Add an icon or photo", color = ink.copy(alpha = 0.6f), fontSize = 15.sp)
                    }
                }
            }
        }
        if (!exporting) {
            // Section color, icon and photo.
            IconButton(
                onClick = onStyle,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp),
            ) {
                Box(Modifier.size(36.dp).background(ink.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Palette, contentDescription = "Section style", tint = ink, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Centered description with a ruled line under each line of text. */
@Composable
private fun DescriptionField(value: String, onChange: (String) -> Unit, ink: Color, showPlaceholder: Boolean) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val style = TextStyle(fontSize = 18.sp, lineHeight = 30.sp, color = ink, textAlign = TextAlign.Center)
    val lineColor = ink.copy(alpha = 0.35f)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = style,
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { layout = it },
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .drawBehind {
                val l = layout ?: return@drawBehind
                val lines = maxOf(1, l.lineCount)
                for (i in 0 until lines) {
                    val y = (if (i < l.lineCount) l.getLineBottom(i) else size.height) + 1.dp.toPx()
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                }
            },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty() && showPlaceholder) Text("Add a description", style = style.copy(color = ink.copy(alpha = 0.45f)))
                inner()
            }
        },
    )
}

/** Color, icon and photo of a section page. Changes apply right away. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SectionStyleSheet(
    page: PageEntity,
    onColor: (Int) -> Unit,
    onIcon: (String?) -> Unit,
    onImage: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val latestOnImage by rememberUpdatedState(onImage)
    val imageAdder = rememberImageAdder { img -> latestOnImage(img.path) }

    QuartzSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Section style", fontSize = 22.sp, fontWeight = FontWeight.Medium)

            SheetHeading("Color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionColorPresets.forEach { c ->
                    Swatch(c, selected = page.sectionColor.toArgb() == c.toArgb(), size = 40.dp, onClick = {
                        onColor(c.toArgb())
                    })
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                        .clickable { custom = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Colorize, contentDescription = "Custom color", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }

            SheetHeading("Photo")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { imageAdder.pickFromGallery() }) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (page.sectionImage == null) "Choose photo" else "Change photo")
                }
                if (page.sectionImage != null) {
                    OutlinedButton(onClick = { onImage(null) }) {
                        Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Remove")
                    }
                }
            }

            SheetHeading(if (page.sectionImage == null) "Icon" else "Icon (shown when there's no photo)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = page.sectionIcon ?: "none"
                IconTile(selected = current == "none", accent = accent, onClick = { onIcon(null) }) {
                    Icon(Icons.Rounded.Block, contentDescription = "No icon", modifier = Modifier.size(22.dp))
                }
                NotebookIcons.all.forEach { (key, vector) ->
                    IconTile(selected = current == key, accent = accent, onClick = { onIcon(key) }) {
                        Icon(vector, contentDescription = key, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
    if (custom) {
        ColorPickerDialog(
            title = "Section color",
            initial = page.sectionColor,
            presets = SectionColorPresets,
            onDismiss = { custom = false },
            onPick = { picked ->
                custom = false
                picked?.let { onColor(it.toArgb()) }
            },
        )
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun IconTile(selected: Boolean, accent: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
