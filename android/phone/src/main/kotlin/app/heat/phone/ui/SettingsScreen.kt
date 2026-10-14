package app.heat.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heat.core.model.CameraConfig
import app.heat.core.settings.Capabilities
import app.heat.core.settings.Section
import app.heat.core.settings.SettingDef
import app.heat.core.settings.SettingKind
import app.heat.core.settings.SettingsCatalog
import app.heat.core.settings.SettingsSearch
import app.heat.glass.GlassHost
import app.heat.glass.GlassShape
import app.heat.glass.GlassState
import app.heat.glass.GlassSurface
import app.heat.glass.Heat
import app.heat.glass.HeatIcon
import app.heat.glass.HeatIconView
import app.heat.glass.HeatText
import app.heat.phone.service.CameraHub
import kotlinx.coroutines.launch

/**
 * Every setting the camera has, in one place you can search.
 *
 * With nothing typed it is the settings in their sections, with chips along the top to jump to one. Type, and the
 * list becomes the matching settings (whole, live, editable right there) with the matched part of each name picked
 * out. It is drawn from [SettingsCatalog], the same list the watch uses, so a setting added there appears here and
 * is findable without touching this file.
 *
 * Glass is used here because the page sits over a still backdrop: one refractive lens, the search bar, and flat
 * glass for the rows.
 */
@Composable
fun SettingsScreen(hub: CameraHub, glass: GlassState, onBack: () -> Unit) {
    val config by hub.host.config.collectAsState()
    val hubState by hub.state.collectAsState()
    val caps = Capabilities(hubState.hasTorch, hubState.manualFocus, hubState.manualExposure, hubState.lensStrategy)
    val edit: ((CameraConfig) -> CameraConfig) -> Unit = { hub.host.editLocally(it) }

    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    // Back closes the search first, then the page.
    BackHandler(enabled = query.isNotEmpty()) { query = ""; focus.clearFocus() }

    val visible = remember(config, caps) { SettingsCatalog.visible(config, caps) }
    val grouped = remember(visible) { visible.groupBy { it.section } }
    val hits = remember(query, visible) { SettingsSearch.search(query, visible) }
    val searching = query.isNotBlank()

    val info = mapOf(
        "pairing" to hubState.pairingFingerprint,
        "wifi" to (hubState.lanAddress ?: "Not on Wi-Fi"),
        "stream" to if (hubState.encoding) "${hubState.streamKbps} kbps · ${hubState.encoderLabel}" + (hubState.carrier?.let { " · ${it.label}" } ?: "") else "Off",
        "camera" to hubState.cameraDiagnostics.ifBlank { "Camera not open yet" }
    )

    GlassHost(
        glass, Modifier.fillMaxSize(),
        backdrop = {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1018), Color(0xFF0A0A10), Color(0xFF14090A)))))
        }
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(glass, HeatIcon.Back, { if (searching) { query = ""; focus.clearFocus() } else onBack() }, size = 46.dp, refract = false)
                Spacer(Modifier.width(14.dp))
                Column {
                    HeatText("Settings", size = 24, weight = FontWeight.Bold)
                    HeatText(config.label, size = 13, color = Heat.TextFaint)
                }
            }

            SearchField(glass, query, { query = it }, onClear = { query = "" }, onSearch = { focus.clearFocus() })
            Spacer(Modifier.height(10.dp))

            if (!searching) {
                // Jump chips.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sections = Section.entries.filter { grouped[it]?.isNotEmpty() == true }
                    sections.forEachIndexed { i, s ->
                        Row(
                            Modifier.height(36.dp).clip(CircleShape).background(Color(0x22FFFFFF)).tappable({ scope.launch { list.animateScrollToItem(i) } })
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HeatIconView(sectionIcon(s), Heat.Ember1, 16.dp)
                            Spacer(Modifier.width(7.dp))
                            HeatText(s.title, size = 13, weight = FontWeight.Medium, color = Heat.TextDim, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
                if (!searching) {
                    val sections = Section.entries.filter { grouped[it]?.isNotEmpty() == true }
                    sections.forEach { section ->
                        item(key = section.name) {
                            SectionCard(glass, section) {
                                val defs = grouped[section].orEmpty()
                                defs.forEachIndexed { i, d ->
                                    if (i > 0) RowDivider()
                                    SettingRow(d, config, info, edit, null)
                                }
                            }
                        }
                    }
                } else if (hits.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            HeatIconView(HeatIcon.Search, Heat.TextFaint, 34.dp)
                            Spacer(Modifier.height(12.dp))
                            HeatText("Nothing matches “${query.trim()}”", size = 17, weight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            HeatText("Try a name or a word you would use for it: exposure, battery, sound, dark, wifi.", size = 14, color = Heat.TextDim, align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                } else {
                    item {
                        HeatText(
                            "${hits.size} ${if (hits.size == 1) "setting" else "settings"}", size = 12, weight = FontWeight.SemiBold,
                            color = Heat.TextFaint, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp)
                        )
                    }
                    items(hits.size, key = { hits[it].def.id }) { i ->
                        val hit = hits[i]
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                            Row(Modifier.padding(start = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                HeatIconView(sectionIcon(hit.def.section), Heat.TextFaint, 12.dp)
                                Spacer(Modifier.width(5.dp))
                                HeatText(hit.def.section.title.uppercase(), size = 10, weight = FontWeight.SemiBold, color = Heat.TextFaint)
                            }
                            GlassSurface(glass, Modifier.fillMaxWidth(), shape = GlassShape.Rounded(22f), refract = false) {
                                SettingRow(hit.def, config, info, edit, hit.titleMatch)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sectionIcon(s: Section): HeatIcon = when (s) {
    Section.CAMERA -> HeatIcon.Camera
    Section.DETECTION -> HeatIcon.Aim
    Section.VIDEO -> HeatIcon.Wifi
    Section.ALERTS -> HeatIcon.Alert
    Section.POWER -> HeatIcon.Bolt
    Section.ABOUT -> HeatIcon.Gear
}

@Composable
private fun SectionCard(glass: GlassState, section: Section, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HeatIconView(sectionIcon(section), Heat.Ember1, 16.dp)
            Spacer(Modifier.width(8.dp))
            HeatText(section.title.uppercase(), size = 12, weight = FontWeight.SemiBold, color = Heat.TextFaint)
            Spacer(Modifier.width(8.dp))
            HeatText(section.blurb, size = 12, color = Color(0x55F5F5F7))
        }
        GlassSurface(glass, Modifier.fillMaxWidth(), shape = GlassShape.Rounded(22f), refract = false) { Column { content() } }
    }
}

/** Draws one setting with the control its kind calls for. */
@Composable
private fun SettingRow(
    d: SettingDef, c: CameraConfig, info: Map<String, String>,
    edit: ((CameraConfig) -> CameraConfig) -> Unit, highlight: IntRange?
) {
    when (val k = d.kind) {
        is SettingKind.Toggle -> ToggleRow(d.title, k.read(c), { on -> edit { k.write(it, on) } }, d.hint, highlight)
        is SettingKind.Choice -> {
            if (k.labels.size <= 4) {
                SegmentRow(d.title, k.labels.mapIndexed { i, l -> l to i }, k.read(c), { i -> edit { k.write(it, i) } }, d.hint, highlight)
            } else {
                PillChoiceRow(d.title, k.labels, k.read(c), { i -> edit { k.write(it, i) } }, d.hint, highlight)
            }
        }
        is SettingKind.Slider -> SliderRow(d.title, k.read(c), k.range, { v -> edit { k.write(it, v) } }, format = k.format, hint = d.hint, step = k.step, highlight = highlight)
        is SettingKind.Stepper -> StepperRow(d.title, k.read(c), k.range, { v -> edit { k.write(it, v) } }, step = k.step, format = k.format, hint = d.hint, highlight = highlight)
        is SettingKind.Text -> TextRow(d.title, k.read(c), { v -> edit { k.write(it, v) } }, d.hint, highlight)
        is SettingKind.Info -> {
            val value = info[k.key].orEmpty()
            if (k.key == "camera") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    SettingLabel(d.title, highlight)
                    Spacer(Modifier.height(6.dp))
                    HeatText(value, size = 12, color = Heat.TextDim)
                }
            } else InfoRow(d.title, value, highlight)
        }
    }
}

@Composable
private fun SearchField(glass: GlassState, query: String, onQuery: (String) -> Unit, onClear: () -> Unit, onSearch: () -> Unit) {
    GlassSurface(
        glass, Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = GlassShape.Capsule, bezel = 14.dp
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            HeatIconView(HeatIcon.Search, if (query.isEmpty()) Heat.TextFaint else Heat.Ember1, 20.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) HeatText("Search settings", size = 16, color = Heat.TextFaint)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Heat.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(Heat.Ember1),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x33FFFFFF)).tappable(onClear), contentAlignment = Alignment.Center) {
                    HeatIconView(HeatIcon.Close, Heat.Text, 14.dp)
                }
            }
        }
    }
}
