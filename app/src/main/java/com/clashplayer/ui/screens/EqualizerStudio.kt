package com.clashplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashplayer.dsp.AudioEngine
import com.clashplayer.dsp.model.AutoEqRepository
import com.clashplayer.dsp.model.EqualizerPreset
import com.clashplayer.dsp.model.ReverbPreset
import com.clashplayer.service.AudioDriverType
import com.clashplayer.service.PlaybackSettings
import com.clashplayer.ui.components.EqualizerSliders
import com.clashplayer.ui.components.GlyphButton
import com.clashplayer.ui.components.NotchToggle
import com.clashplayer.ui.components.TickSelector
import com.clashplayer.ui.theme.HudStyle
import com.clashplayer.ui.components.ObsidianPanel
import com.clashplayer.ui.components.RotaryKnob
import com.clashplayer.ui.theme.ObsidianRaised
import com.clashplayer.ui.theme.EdgeLight
import com.clashplayer.ui.theme.Phosphor
import com.clashplayer.ui.theme.Ember
import com.clashplayer.ui.theme.Ink
import com.clashplayer.ui.theme.InkDim
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerStudio(
    audioEngine: AudioEngine,
    currentDriver: AudioDriverType,
    onDriverSelected: (AudioDriverType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    settings: PlaybackSettings = PlaybackSettings(),
    onReplayGainChanged: () -> Unit = {},
    outputControls: OutputControls? = null,
    onOpenSignalPath: () -> Unit = {}
) {
    val eqState by audioEngine.equalizerStateFlow.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var autoEqQuery by remember { mutableStateOf("") }
    var dvcEnabled by remember { mutableStateOf(audioEngine.dvcEnabled) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphButton(Icons.Default.ArrowBack, "Back", onBack, size = 40.dp, lit = true)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Studio", style = MaterialTheme.typography.headlineLarge, color = Ink)
                Text("64-BIT DSP  ·  ${eqState.bands.size} BANDS  ·  ${if (eqState.enabled) "ENGAGED" else "BYPASSED"}", style = HudStyle)
            }
            GlyphButton(Icons.Default.BugReport, "Diagnostics & Logs", { showDiagnosticsSheet = true }, size = 40.dp)
            Spacer(modifier = Modifier.width(6.dp))
            NotchToggle(eqState.enabled, { audioEngine.setEqualizerEnabled(it) })
        }

        TickSelector(listOf("Graphic EQ", "Tone & Space", "AutoEQ", "Engine", "Output"), selectedTab, { selectedTab = it })

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> GraphicEqTab(
                audioEngine = audioEngine,
                bands = eqState.bands,
                presets = EqualizerPreset.BUILT_IN_PRESETS,
                selectedPreset = eqState.name,
                onSelectPreset = { audioEngine.applyPreset(it) },
                revision = eqState.revision,
                sampleRate = audioEngine.sampleRate,
                onBandChange = { idx, gain -> audioEngine.setBandGain(idx, gain) },
                onReset = { audioEngine.resetBands() }
            )

            1 -> ToneAndSpaceTab(
                audioEngine = audioEngine
            )

            2 -> AutoEqTab(
                query = autoEqQuery,
                onQueryChange = { autoEqQuery = it },
                onApplyPreset = { preset -> audioEngine.applyPreset(preset) }
            )

            3 -> EngineTab(audioEngine, settings, onReplayGainChanged, outputControls, onOpenSignalPath)

            4 -> AudioOutputTab(
                currentDriver = currentDriver,
                isDvcActive = dvcEnabled,
                onToggleDvc = { dvcEnabled = it; audioEngine.dvcEnabled = it },
                onDriverSelected = onDriverSelected,
                onOpenDiagnostics = { showDiagnosticsSheet = true }
            )
        }
    }

    if (showDiagnosticsSheet) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDiagnosticsSheet = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                DiagnosticsSheet(onDismiss = { showDiagnosticsSheet = false })
            }
        }
    }
}

@Composable
private fun GraphicEqTab(
    audioEngine: AudioEngine,
    bands: List<com.clashplayer.dsp.model.EqualizerBand>,
    presets: List<EqualizerPreset>,
    selectedPreset: String,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onReset: () -> Unit,
    revision: Int,
    sampleRate: Int,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Preset Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphButton(Icons.Default.Refresh, "Reset EQ", onReset, size = 40.dp)
                TickSelector(presets.map { it.name }, presets.indexOfFirst { it.name == selectedPreset }, { onSelectPreset(presets[it]) })
            }
        }

        // 10-Band Slider Controls + Curve
        item {
            ObsidianPanel(
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    EqualizerSliders(
                        bands = bands,
                        onBandGainChanged = onBandChange,
                        revision = revision,
                        sampleRate = sampleRate
                    )
                }
            }
        }
    }
}

@Composable
private fun ToneAndSpaceTab(
    audioEngine: AudioEngine
) {
    var preampDb by remember { mutableStateOf(audioEngine.preampDb) }
    var bassDb by remember { mutableStateOf(audioEngine.bassDb) }
    var trebleDb by remember { mutableStateOf(audioEngine.trebleDb) }
    var stereoWidth by remember { mutableStateOf(audioEngine.stereoProcessor.stereoWidth) }
    var balance by remember { mutableStateOf(audioEngine.stereoProcessor.balance) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            var clarityOn by remember { mutableStateOf(audioEngine.clarityProcessor.enabled) }
            var clarityPresetIdx by remember { mutableIntStateOf(audioEngine.clarityProcessor.currentPreset.index) }
            var clarityWet by remember { mutableStateOf(audioEngine.clarityProcessor.wetMix) }
            var clarityWidth by remember { mutableStateOf(audioEngine.clarityProcessor.stereoWidth) }
            var clarityExciter by remember { mutableStateOf(audioEngine.clarityProcessor.exciterAmount) }

            Text(
                text = "Studio Master Clarity & Separation",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(10.dp))

            ObsidianPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Acoustic Clarity & Anti-Mud", style = MaterialTheme.typography.titleSmall, color = Ink)
                            Text(
                                "8-stage contouring, 130Hz mono-bass separation & tape exciter",
                                style = HudStyle
                            )
                        }
                        NotchToggle(clarityOn, {
                            clarityOn = it
                            audioEngine.setClarityEnabled(it)
                        })
                    }

                    TickSelector(
                        options = listOf("Reference", "Speaker", "Headphone", "DAC"),
                        selected = clarityPresetIdx,
                        onSelect = {
                            clarityPresetIdx = it
                            audioEngine.setClarityPreset(it)
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        RotaryKnob(
                            value = clarityWet * 100f,
                            min = 0f, max = 100f,
                            label = "Wet Mix",
                            unit = "%",
                            onValueChange = {
                                val w = it / 100f
                                clarityWet = w
                                audioEngine.setClarityWetMix(w)
                            }
                        )

                        RotaryKnob(
                            value = clarityWidth,
                            min = 1.0f, max = 1.6f,
                            label = "Separation",
                            unit = "x",
                            onValueChange = {
                                clarityWidth = it
                                audioEngine.setClarityStereoWidth(it)
                            }
                        )

                        RotaryKnob(
                            value = clarityExciter * 100f,
                            min = 0f, max = 35f,
                            label = "Tape Sheen",
                            unit = "%",
                            onValueChange = {
                                val ex = it / 100f
                                clarityExciter = ex
                                audioEngine.setClarityExciterAmount(ex)
                            }
                        )
                    }

                    Text(
                        "24Hz HPF · 72Hz BASS · 280Hz ANTI-MUD · 3.4kHz DETAIL · 10.5kHz AIR · 130Hz MONO-BASS",
                        style = HudStyle.copy(color = Phosphor),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        item {
            var binauralOn by remember { mutableStateOf(audioEngine.binauralSpatializer.enabled) }
            var roomAmbience by remember { mutableStateOf(audioEngine.binauralSpatializer.roomAmbience) }
            var soundstageWidth by remember { mutableStateOf(audioEngine.binauralSpatializer.soundstageWidth) }
            var directMix by remember { mutableStateOf(audioEngine.binauralSpatializer.directMix) }

            Text(
                text = "Binaural Room Spatializer (Neumann KU 100)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(10.dp))

            ObsidianPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("3D Headphone Spatializer & Studio Room", style = MaterialTheme.typography.titleSmall, color = Ink)
                            Text(
                                "Georg Neumann KU 100 HRTF dummy head pinna notch, ITD/ILD acoustic shadow & BRIR room reflections",
                                style = HudStyle
                            )
                        }
                        NotchToggle(binauralOn, {
                            binauralOn = it
                            audioEngine.setBinauralEnabled(it)
                        })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        RotaryKnob(
                            value = roomAmbience * 100f,
                            min = 0f, max = 100f,
                            label = "Room Ambience",
                            unit = "%",
                            onValueChange = {
                                val a = it / 100f
                                roomAmbience = a
                                audioEngine.setBinauralRoomAmbience(a)
                            }
                        )

                        RotaryKnob(
                            value = soundstageWidth,
                            min = 0.5f, max = 2.0f,
                            label = "Soundstage",
                            unit = "x",
                            onValueChange = {
                                soundstageWidth = it
                                audioEngine.setBinauralSoundstageWidth(it)
                            }
                        )

                        RotaryKnob(
                            value = directMix * 100f,
                            min = 20f, max = 100f,
                            label = "Direct Mix",
                            unit = "%",
                            onValueChange = {
                                val d = it / 100f
                                directMix = d
                                audioEngine.setBinauralDirectMix(d)
                            }
                        )
                    }

                    Text(
                        "KU 100 DUMMY HEAD · 280µs ITD · 6kHz PINNA NOTCH · 3.2kHz RESONANCE · BRIR CONTROL ROOM",
                        style = HudStyle.copy(color = Phosphor),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        item {
            Text(
                text = "Tone Controls",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(12.dp))

            ObsidianPanel(
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RotaryKnob(
                        value = preampDb,
                        min = -15f, max = 15f,
                        label = "Preamp",
                        unit = "dB",
                        onValueChange = {
                            preampDb = it
                            audioEngine.setPreamp(it)
                        }
                    )

                    RotaryKnob(
                        value = bassDb,
                        min = -12f, max = 12f,
                        label = "Bass",
                        unit = "dB",
                        onValueChange = {
                            bassDb = it
                            audioEngine.setToneControls(bass = it, treble = trebleDb)
                        }
                    )

                    RotaryKnob(
                        value = trebleDb,
                        min = -12f, max = 12f,
                        label = "Treble",
                        unit = "dB",
                        onValueChange = {
                            trebleDb = it
                            audioEngine.setToneControls(bass = bassDb, treble = it)
                        }
                    )
                }
            }
        }

        item {
            Text(
                text = "Spatial Imaging & Stereo Haas",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(12.dp))

            ObsidianPanel(
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RotaryKnob(
                        value = stereoWidth,
                        min = 0f, max = 2f,
                        label = "Stereo Haas",
                        unit = "x",
                        onValueChange = {
                            stereoWidth = it
                            audioEngine.setStereoProcessor(width = it, balance = balance)
                        }
                    )

                    RotaryKnob(
                        value = balance,
                        min = -1f, max = 1f,
                        label = "Balance",
                        unit = "",
                        onValueChange = {
                            balance = it
                            audioEngine.setStereoProcessor(width = stereoWidth, balance = it)
                        }
                    )
                }
            }
        }

        item {
            Text(
                text = "Studio Reverb",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(12.dp))

            ObsidianPanel(
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var reverbOn by remember { mutableStateOf(audioEngine.reverbProcessor.enabled) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reverb Engine", color = Ink, fontWeight = FontWeight.SemiBold)
                        NotchToggle(reverbOn, {
                            reverbOn = it
                            audioEngine.reverbProcessor.enabled = it
                        })
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    var reverbPreset by remember { mutableIntStateOf(-1) }
                    val reverbs = ReverbPreset.entries
                    TickSelector(reverbs.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }, reverbPreset, {
                        reverbPreset = it
                        reverbOn = true
                        audioEngine.applyReverbPreset(reverbs[it])
                    })
                }
            }
        }
    }
}

@Composable
private fun AutoEqTab(
    query: String,
    onQueryChange: (String) -> Unit,
    onApplyPreset: (EqualizerPreset) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { AutoEqRepository(context.cacheDir) }
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<AutoEqRepository.Entry>>(emptyList()) }
    var status by remember { mutableStateOf("Loading AutoEq index…") }
    var applied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        status = try {
            results = withContext(Dispatchers.IO) { repo.search(query) }
            if (results.isEmpty()) "No AutoEq profile matches \"$query\"" else ""
        } catch (e: Exception) {
            "Couldn't load the AutoEq index: ${e.message}"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search AutoEq headphones (e.g. HD 650, AirPods Pro)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = InkDim) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Phosphor,
                unfocusedBorderColor = EdgeLight,
                focusedContainerColor = ObsidianRaised,
                unfocusedContainerColor = ObsidianRaised
            )
        )

        Spacer(modifier = Modifier.height(8.dp))
        val note = applied?.let { "Applied: $it" } ?: status
        if (note.isNotEmpty()) {
            Text(note, color = InkDim, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { entry ->
                ObsidianPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                try {
                                    val preset = withContext(Dispatchers.IO) { repo.loadPreset(entry) }
                                    onApplyPreset(preset)
                                    applied = preset.name
                                } catch (e: Exception) {
                                    applied = null
                                    status = "Couldn't load ${entry.name}: ${e.message}"
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Measured by ${entry.source}" + (entry.rig?.let { " on $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = InkDim
                            )
                        }

                        Text(
                            text = "APPLY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Phosphor
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioOutputTab(
    currentDriver: AudioDriverType,
    isDvcActive: Boolean,
    onToggleDvc: (Boolean) -> Unit,
    onDriverSelected: (AudioDriverType) -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Direct Volume Control (DVC)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(8.dp))

            ObsidianPanel(
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Direct Volume Control", color = Ink, fontWeight = FontWeight.Bold)
                        Text(
                            "Volume is applied in the 64-bit DSP chain, capped at 0 dB so it never clips. Bypassed in bit-perfect mode.",
                            color = InkDim,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                        )
                    }

                    NotchToggle(isDvcActive, onToggleDvc)
                }
            }
        }

        item {
            Text(
                text = "Audio Output Driver",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(8.dp))

            val drivers = listOf(
                Triple(AudioDriverType.BIT_PERFECT_DIRECT, "Bit-Perfect Direct (follow_sr)", "Own USB driver: the DAC runs at each track's exact rate, any Android version. DSP is bypassed."),
                Triple(AudioDriverType.AAUDIO, "AAudio High Performance", "Native low-latency hardware MMAP buffer"),
                Triple(AudioDriverType.OPENSL_ES, "OpenSL ES", "Native legacy low-latency audio engine"),
                Triple(AudioDriverType.AUDIOTRACK_FLOAT, "AudioTrack 32-bit Float", "Standard Android high-precision float streaming")
            )

            drivers.forEach { (driver, title, desc) ->
                val isSelected = driver == currentDriver
                ObsidianPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDriverSelected(driver) }
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = if (isSelected) Phosphor else Ink,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = desc,
                                color = InkDim,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                            )
                        }

                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Active", tint = Phosphor)
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Diagnostics & Crash Logs",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink
            )
            Spacer(modifier = Modifier.height(8.dp))

            ObsidianPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDiagnostics)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Export Logs & Bug Reports",
                            color = Phosphor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "View audio pipeline logs, buffer underruns, fatal crash reports, and export diagnostics.",
                            color = InkDim,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Diagnostics",
                        tint = Phosphor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(170.dp))
        }
    }
}
