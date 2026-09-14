package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.*

/**
 * 19 Comprehensive Camera Settings Categories:
 * Camera, Photo, Video, Cinema, Lens, Zoom, Focus, Exposure, HDR, AI,
 * Stabilization, Codec, Resolution/FPS, Audio, Grid, Gesture,
 * UI Customization, Performance, and Advanced.
 */
enum class SettingsSubPage(val title: String, val subtitle: String, val icon: ImageVector) {
    CAMERA("Camera", "General preferences, selfie mirror & sounds", Icons.Outlined.Camera),
    PHOTO("Photo", "Megapixel mode, RAW sensor, JPEG quality & filters", Icons.Outlined.CameraAlt),
    FEATURES("Features", "AI Zoom, computational super-resolution & smart capture", Icons.Outlined.AutoAwesome),
    VIDEO("Video", "Quality presets, stabilization, frame rates & bitrates", Icons.Outlined.Videocam),
    CINEMA("Cinema", "10-bit HLG, Flat Log, zebra stripes & waveforms", Icons.Outlined.MovieCreation),
    LENS("Lens", "Multi-lens switching, focal lengths & aux scan", Icons.Outlined.CenterFocusStrong),
    ZOOM("Zoom", "Quick presets, digital stabilization & transition speed", Icons.Outlined.ZoomIn),
    FOCUS("Focus", "Tap to focus, manual focus distance & AF lock", Icons.Outlined.FilterCenterFocus),
    EXPOSURE("Exposure", "EV compensation range, manual ISO & shutter speed", Icons.Outlined.WbSunny),
    HDR("HDR", "Auto HDR capture, video tone-mapping & night fusion", Icons.Outlined.HdrOn),
    AI("AI", "Auto-framing, portrait bokeh depth & skin smoothing", Icons.Outlined.AutoAwesome),
    STABILIZATION("Stabilization", "Hybrid physical OIS, electronic EIS & action mode", Icons.Outlined.HdrAuto),
    CODEC("Codec", "HEVC / H.265 compression & AAC audio format", Icons.Outlined.Code),
    RESOLUTION_FPS("Resolution / FPS", "Sensor resolution matrix & recording framerates", Icons.Outlined.Hd),
    AUDIO("Audio", "Microphone recording, stereo array & wind filter", Icons.Outlined.Mic),
    GRID("Grid", "Rule of thirds, golden ratio & tilt leveler", Icons.Outlined.GridOn),
    GESTURE("Gesture", "Volume key actions, double-tap & swipe controls", Icons.Outlined.TouchApp),
    UI_CUSTOMIZATION("UI Customization", "Pixel, Minimal Pro, Cyber Glass, DSLR & layout editor", Icons.Outlined.DashboardCustomize),
    PERFORMANCE("Performance", "Viewfinder refresh rate, GPU boost & thermals", Icons.Outlined.Speed),
    ADVANCED("Advanced", "Camera2 HAL hardware level, diagnostics & reset", Icons.Outlined.Build)
}

/**
 * Modern, Categorized Settings Sheet containing all 19 camera categories.
 * Fully interactive, connected to real app state and persistence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    isOpen: Boolean,
    cameraMode: CameraMode,
    capabilities: HardwareCapabilities,
    availableLenses: List<LensInfo> = emptyList(),
    selectedLens: LensInfo? = null,
    selectedPhotoResolution: CameraResolution?,
    selectedVideoResolution: CameraResolution?,
    photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12,
    isRefocusPhotoEnabled: Boolean = false,
    isAiZoomEnabled: Boolean = false,
    aiZoomQuality: com.example.camera.dbsr.AiZoomQuality = com.example.camera.dbsr.AiZoomQuality.AUTO,
    videoFps: Int = 30,
    videoBitrate: VideoBitrateOption = VideoBitrateOption.AUTO,
    isVideoStabilizationEnabled: Boolean = true,
    isAudioEnabled: Boolean = true,
    isRawEnabled: Boolean = false,
    saveSelfieAsPreviewed: Boolean = true,
    gridType: GridType = GridType.NONE,
    cinemaConfig: CinemaConfig = CinemaConfig(),
    cinemaCapabilities: CinemaHardwareCapabilities = CinemaHardwareCapabilities(),
    viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL,
    hybridStabilizationConfig: HybridStabilizationConfig = HybridStabilizationConfig(),
    nightConfig: NightConfig = NightConfig(),
    tapFocusConfig: TapFocusConfig = TapFocusConfig(),
    // Extended Settings State
    videoCodec: String = "HEVC",
    jpegQuality: Int = 95,
    volumeKeyAction: String = "SHUTTER",
    doubleTapAction: String = "FLIP",
    shutterFeedback: String = "SOUND_AND_HAPTIC",
    antibandingMode: String = "AUTO",
    windNoiseReduction: Boolean = true,
    audioSource: String = "CAMCORDER",
    horizonLeveler: Boolean = true,
    viewfinderFps: Int = 60,
    thermalProtection: Boolean = true,
    isAutoHdrEnabled: Boolean = true,
    isAiAutoFramingEnabled: Boolean = false,
    currentZoom: Float = 1.0f,
    exposureCompensation: Int = 0,
    manualIso: Int? = null,
    manualShutterSpeedNs: Long? = null,
    focusMode: FocusMode = FocusMode.CONTINUOUS,
    manualFocusDistance: Float = 0.0f,
    portraitConfig: PortraitConfig = PortraitConfig(),
    selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL,
    // Callbacks
    onLensSelected: (LensInfo) -> Unit = {},
    onForceDeepScan: () -> Unit = {},
    onPhotoResolutionSelected: (CameraResolution) -> Unit = {},
    onPhotoMegapixelModeSelected: (PhotoMegapixelMode) -> Unit = {},
    onRefocusPhotoToggle: (Boolean) -> Unit = {},
    onAiZoomToggle: (Boolean) -> Unit = {},
    onAiZoomQualitySelect: (com.example.camera.dbsr.AiZoomQuality) -> Unit = {},
    onVideoResolutionSelected: (CameraResolution) -> Unit = {},
    onViewfinderResolutionSelected: (ViewfinderResolution) -> Unit = {},
    onVideoFpsSelected: (Int) -> Unit = {},
    onVideoBitrateSelected: (VideoBitrateOption) -> Unit = {},
    onStabilizationToggle: (Boolean) -> Unit = {},
    onHybridStabilizationChange: (HybridStabilizationConfig) -> Unit = {},
    onNightConfigChange: (NightConfig) -> Unit = {},
    onTapFocusConfigChange: (TapFocusConfig) -> Unit = {},
    onAudioToggle: () -> Unit = {},
    onRawToggle: () -> Unit = {},
    onSaveSelfieAsPreviewedToggle: (Boolean) -> Unit = {},
    onGridTypeSelected: (GridType) -> Unit = {},
    onCinemaConfigChange: (CinemaConfig) -> Unit = {},
    onVideoCodecSelected: (String) -> Unit = {},
    onJpegQualitySelected: (Int) -> Unit = {},
    onVolumeKeyActionSelected: (String) -> Unit = {},
    onDoubleTapActionSelected: (String) -> Unit = {},
    onShutterFeedbackSelected: (String) -> Unit = {},
    onAntibandingModeSelected: (String) -> Unit = {},
    onWindNoiseReductionToggle: (Boolean) -> Unit = {},
    onAudioSourceSelected: (String) -> Unit = {},
    onHorizonLevelerToggle: (Boolean) -> Unit = {},
    onViewfinderFpsSelected: (Int) -> Unit = {},
    onThermalProtectionToggle: (Boolean) -> Unit = {},
    onAutoHdrToggle: (Boolean) -> Unit = {},
    onAiAutoFramingToggle: (Boolean) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    onExposureCompensationChange: (Int) -> Unit = {},
    onManualIsoChange: (Int?) -> Unit = {},
    onManualShutterSpeedChange: (Long?) -> Unit = {},
    onFocusModeChange: (FocusMode) -> Unit = {},
    onManualFocusDistanceChange: (Float) -> Unit = {},
    onPortraitConfigChange: (PortraitConfig) -> Unit = {},
    onPhotoFilterSelected: (PhotoFilter) -> Unit = {},
    onResetAllSettings: () -> Unit = {},
    // UI Customization callbacks
    uiCustomizationState: UiCustomizationState = UiCustomizationState(),
    onSelectTemplate: (UiTemplateType) -> Unit = {},
    onUpdateGlobalLayoutConfig: (ModeLayoutConfig) -> Unit = {},
    onUpdateModeLayoutConfig: (CameraMode, ModeLayoutConfig) -> Unit = { _, _ -> },
    onResetModeLayoutConfig: (CameraMode) -> Unit = {},
    onSaveCustomPreset: (String, ModeLayoutConfig) -> Unit = { _, _ -> },
    onLoadCustomPreset: (CustomUiPreset) -> Unit = {},
    onDeleteCustomPreset: (String) -> Unit = {},
    onResetAllToTemplate: (UiTemplateType) -> Unit = {},
    onOpenCustomUiStudio: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    var activeSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFFF8F9FA),
        contentColor = Color(0xFF1F2937),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color(0xFFD1D5DB))
        },
        modifier = modifier.testTag("settings_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeSubPage != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { activeSubPage = null },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE5E7EB))
                                .testTag("settings_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color(0xFF1F2937),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = activeSubPage?.title ?: "Settings",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111827)
                            )
                            Text(
                                text = activeSubPage?.subtitle ?: "Camera settings category",
                                fontSize = 11.5.sp,
                                color = Color(0xFF6B7280),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Column {
                        Text(
                            text = "Settings",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827)
                        )
                        Text(
                            text = "Complete controls, engines & device hardware",
                            fontSize = 12.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5E7EB))
                        .testTag("settings_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF374151),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body: Category Directory vs Dedicated SubPage
            AnimatedContent(
                targetState = activeSubPage,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "settingsPageTransition"
            ) { subPage ->
                if (subPage == null) {
                    // MAIN DIRECTORY LIST: All 19 categories
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsSubPage.entries.forEach { page ->
                            item(key = page.name) {
                                val summary = when (page) {
                                    SettingsSubPage.CAMERA -> if (saveSelfieAsPreviewed) "Mirror On · $shutterFeedback" else "Standard · $shutterFeedback"
                                    SettingsSubPage.PHOTO -> {
                                        val refocusTag = if (isRefocusPhotoEnabled) " · Refocus ON" else ""
                                        if (photoMegapixelMode == PhotoMegapixelMode.M50) "50MP Ultra$refocusTag · JPEG $jpegQuality%" else "12MP Standard$refocusTag · JPEG $jpegQuality%"
                                    }
                                    SettingsSubPage.FEATURES -> if (isAiZoomEnabled) "AI Zoom ON · ${aiZoomQuality.label} Quality" else "AI Zoom OFF · Deep Burst SR"
                                    SettingsSubPage.VIDEO -> "${selectedVideoResolution?.let { "${it.width}x${it.height}" } ?: "4K"} · ${videoFps}fps · $videoCodec"
                                    SettingsSubPage.CINEMA -> "${cinemaConfig.colorProfile.label} · ${cinemaConfig.logBitDepth.label}"
                                    SettingsSubPage.LENS -> "${availableLenses.size} lenses available · Deep Scan"
                                    SettingsSubPage.ZOOM -> "Current: %.1fx · Smooth transition".format(currentZoom)
                                    SettingsSubPage.FOCUS -> if (tapFocusConfig.isTapToFocusEnabled) "Tap to Focus · ${focusMode.name}" else focusMode.name
                                    SettingsSubPage.EXPOSURE -> "EV ${if (exposureCompensation >= 0) "+%.1f".format(exposureCompensation/3f) else "%.1f".format(exposureCompensation/3f)} · ISO ${manualIso ?: "Auto"}"
                                    SettingsSubPage.HDR -> if (isAutoHdrEnabled) "Auto HDR ON · Ghost suppression" else "Standard Dynamic Range"
                                    SettingsSubPage.AI -> if (isAiAutoFramingEnabled) "Auto-framing ON · Bokeh ${portraitConfig.simulatedAperture}" else "Bokeh ${portraitConfig.simulatedAperture} · Face Retouch"
                                    SettingsSubPage.STABILIZATION -> if (isVideoStabilizationEnabled && hybridStabilizationConfig.isHybridEnabled) "Coordinated OIS + EIS" else if (isVideoStabilizationEnabled) "Standard EIS" else "Off"
                                    SettingsSubPage.CODEC -> "$videoCodec (High Efficiency) · AAC Audio"
                                    SettingsSubPage.RESOLUTION_FPS -> "${selectedPhotoResolution?.let { "${it.width}x${it.height}" } ?: "Native"} · ${videoFps} FPS"
                                    SettingsSubPage.AUDIO -> if (isAudioEnabled) "Recording ON · $audioSource · Wind filter" else "Muted"
                                    SettingsSubPage.GRID -> "${gridType.name} · Leveler ${if (horizonLeveler) "ON" else "OFF"}"
                                    SettingsSubPage.GESTURE -> "Volume: $volumeKeyAction · Double-Tap: $doubleTapAction"
                                    SettingsSubPage.UI_CUSTOMIZATION -> "${uiCustomizationState.selectedTemplate.title} Active"
                                    SettingsSubPage.PERFORMANCE -> "${viewfinderFps}fps Viewfinder · GPU Accelerated"
                                    SettingsSubPage.ADVANCED -> "Camera2 HAL · Sensor Array · Factory Reset"
                                }

                                SettingsCategoryTile(
                                    page = page,
                                    summary = summary,
                                    onClick = { activeSubPage = page }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(28.dp))
                        }
                    }
                } else {
                    // DEDICATED SUBPAGES
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (subPage) {
                            // 1. CAMERA
                            SettingsSubPage.CAMERA -> {
                                item {
                                    SettingsSectionCard(title = "General Camera Preferences") {
                                        LightToggleRow(
                                            title = "Save Selfie as Previewed",
                                            subtitle = "Mirrors front-facing photos to match what you see in the viewfinder",
                                            isChecked = saveSelfieAsPreviewed,
                                            onToggle = { onSaveSelfieAsPreviewedToggle(!saveSelfieAsPreviewed) }
                                        )
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Shutter Sound & Haptic Feedback", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("Sound & Haptics", shutterFeedback == "SOUND_AND_HAPTIC", { onShutterFeedbackSelected("SOUND_AND_HAPTIC") }, Modifier.weight(1f))
                                            LightSelectPill("Haptic Only", shutterFeedback == "HAPTIC_ONLY", { onShutterFeedbackSelected("HAPTIC_ONLY") }, Modifier.weight(1f))
                                            LightSelectPill("Silent", shutterFeedback == "SILENT", { onShutterFeedbackSelected("SILENT") }, Modifier.weight(1f))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Anti-Banding (Flicker Reduction)", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("Auto", antibandingMode == "AUTO", { onAntibandingModeSelected("AUTO") }, Modifier.weight(1f))
                                            LightSelectPill("50 Hz", antibandingMode == "50HZ", { onAntibandingModeSelected("50HZ") }, Modifier.weight(1f))
                                            LightSelectPill("60 Hz", antibandingMode == "60HZ", { onAntibandingModeSelected("60HZ") }, Modifier.weight(1f))
                                            LightSelectPill("Off", antibandingMode == "OFF", { onAntibandingModeSelected("OFF") }, Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            // 2. PHOTO
                            SettingsSubPage.PHOTO -> {
                                item {
                                    SettingsSectionCard(title = "Photo Capture & Quality") {
                                        LightToggleRow(
                                            title = "Refocus Photo",
                                            subtitle = "Multi-plane capture for interactive post-capture focus & 3D parallax",
                                            isChecked = isRefocusPhotoEnabled,
                                            onToggle = { onRefocusPhotoToggle(!isRefocusPhotoEnabled) }
                                        )
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Real-ESRGAN AI Super Resolution", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Text(
                                            "Official xinntao/Real-ESRGAN neural upscaling with GPU acceleration, dynamic tiling & crash protection",
                                            fontSize = 11.5.sp,
                                            color = Color(0xFF6B7280),
                                            lineHeight = 15.sp
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            LightSelectPill("12M Off", photoMegapixelMode == PhotoMegapixelMode.M12, { onPhotoMegapixelModeSelected(PhotoMegapixelMode.M12) }, Modifier.weight(1f))
                                            LightSelectPill("50MP", photoMegapixelMode == PhotoMegapixelMode.M50, { onPhotoMegapixelModeSelected(PhotoMegapixelMode.M50) }, Modifier.weight(1f))
                                            LightSelectPill("100MP", photoMegapixelMode == PhotoMegapixelMode.M100, { onPhotoMegapixelModeSelected(PhotoMegapixelMode.M100) }, Modifier.weight(1f))
                                            LightSelectPill("200MP", photoMegapixelMode == PhotoMegapixelMode.M200, { onPhotoMegapixelModeSelected(PhotoMegapixelMode.M200) }, Modifier.weight(1f))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        if (capabilities.supportsRaw) {
                                            LightToggleRow(
                                                title = "RAW (DNG) Sensor Capture",
                                                subtitle = "Saves uncompressed 16-bit linear sensor DNG alongside JPEG",
                                                isChecked = isRawEnabled,
                                                onToggle = { onRawToggle() }
                                            )
                                            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        }
                                        Text("JPEG Compression Quality", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("100% Super Fine", jpegQuality == 100, { onJpegQualitySelected(100) }, Modifier.weight(1f))
                                            LightSelectPill("95% Fine", jpegQuality == 95, { onJpegQualitySelected(95) }, Modifier.weight(1f))
                                            LightSelectPill("85% Standard", jpegQuality == 85, { onJpegQualitySelected(85) }, Modifier.weight(1f))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Color Style Preset", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            PhotoFilter.entries.take(4).forEach { filter ->
                                                LightSelectPill(filter.displayName, selectedPhotoFilter == filter, { onPhotoFilterSelected(filter) }, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. FEATURES (AI Zoom - Deep Burst Super-Resolution)
                            SettingsSubPage.FEATURES -> {
                                item {
                                    SettingsSectionCard(title = "AI Zoom (Deep Burst Super-Resolution)") {
                                        LightToggleRow(
                                            title = "AI Zoom",
                                            subtitle = "Reconstructs ultra-fine detail on zoomed subjects using the Deep Burst Super-Resolution neural network",
                                            isChecked = isAiZoomEnabled
                                        ) {
                                            onAiZoomToggle(!isAiZoomEnabled)
                                        }

                                        if (isAiZoomEnabled) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(
                                                text = "Processing Quality",
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1F2937)
                                            )
                                            Text(
                                                text = "Auto captures a rapid 3-frame burst for low latency; High captures a 5-frame deep burst for maximal detail.",
                                                fontSize = 11.5.sp,
                                                color = Color(0xFF6B7280)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                com.example.camera.dbsr.AiZoomQuality.entries.forEach { q ->
                                                    LightSelectPill(
                                                        label = q.label,
                                                        isSelected = aiZoomQuality == q,
                                                        onClick = { onAiZoomQualitySelect(q) },
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                item {
                                    SettingsSectionCard(title = "DBSR Neural Architecture") {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Optical Flow Alignment",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF1F2937)
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFEFF6FF)
                                                ) {
                                                    Text(
                                                        text = "PWC-Net Pyramid",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF2563EB),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "Sub-pixel feature warping and correlation cost volume align hand-held frame shifts with sub-pixel precision.",
                                                fontSize = 11.sp,
                                                color = Color(0xFF6B7280)
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))
                                            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                            Spacer(modifier = Modifier.height(4.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Bayer RAW & Attention Merging",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF1F2937)
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFF0FDF4)
                                                ) {
                                                    Text(
                                                        text = "4-Channel Bayer",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF16A34A),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "Preserves pure sensor Bayer quads [R, G1, G2, B] with spatial softmax attention weighting across burst frames.",
                                                fontSize = 11.sp,
                                                color = Color(0xFF6B7280)
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))
                                            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                            Spacer(modifier = Modifier.height(4.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Super-Resolution Decoder",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF1F2937)
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFFAF5FF)
                                                ) {
                                                    Text(
                                                        text = "4x Sub-Pixel",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF7C3AED),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "Two-stage PixelShuffle expansion reconstructs high-resolution RGB image without blur or pixelation.",
                                                fontSize = 11.sp,
                                                color = Color(0xFF6B7280)
                                            )
                                        }
                                    }
                                }
                            }
                            SettingsSubPage.VIDEO -> {
                                item {
                                    SettingsSectionCard(title = "Video Quality & Recording") {
                                        Text("Frame Rate (FPS)", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("24 FPS (Cinema)", videoFps == 24, { onVideoFpsSelected(24) }, Modifier.weight(1f))
                                            LightSelectPill("30 FPS", videoFps == 30, { onVideoFpsSelected(30) }, Modifier.weight(1f))
                                            LightSelectPill("60 FPS", videoFps == 60, { onVideoFpsSelected(60) }, Modifier.weight(1f))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Bitrate Encoding Profile", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        VideoBitrateOption.entries.forEach { option ->
                                            val desc = if (option.bps > 0) "${option.bps / 1_000_000} Mbps target bitrate" else "Device recommended default"
                                            LightOptionRow(option.title, desc, videoBitrate == option) { onVideoBitrateSelected(option) }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        LightToggleRow(
                                            title = "Video Stabilization",
                                            subtitle = "Reduces handheld shake using electronic image stabilization",
                                            isChecked = isVideoStabilizationEnabled,
                                            onToggle = { onStabilizationToggle(!isVideoStabilizationEnabled) }
                                        )
                                    }
                                }
                            }

                            // 4. CINEMA
                            SettingsSubPage.CINEMA -> {
                                item {
                                    SettingsSectionCard(title = "Cinema & 10-Bit Log Engine") {
                                        Text("Log Bit Depth", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LogBitDepth.entries.forEach { depth ->
                                                LightSelectPill(depth.label, cinemaConfig.logBitDepth == depth, { onCinemaConfigChange(cinemaConfig.copy(logBitDepth = depth)) }, Modifier.weight(1f))
                                            }
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Color Profile", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        CinemaColorProfile.entries.forEach { prof ->
                                            LightOptionRow(prof.label, prof.description, cinemaConfig.colorProfile == prof) { onCinemaConfigChange(cinemaConfig.copy(colorProfile = prof)) }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        LightToggleRow("Focus Peaking Overlay", "Highlights sharp edges in real-time", cinemaConfig.isFocusPeakingEnabled) {
                                            onCinemaConfigChange(cinemaConfig.copy(isFocusPeakingEnabled = !cinemaConfig.isFocusPeakingEnabled))
                                        }
                                        LightToggleRow("Live Waveform Monitor", "Displays real-time luminance histogram", cinemaConfig.isWaveformEnabled) {
                                            onCinemaConfigChange(cinemaConfig.copy(isWaveformEnabled = !cinemaConfig.isWaveformEnabled))
                                        }
                                    }
                                }
                            }

                            // 5. LENS
                            SettingsSubPage.LENS -> {
                                item {
                                    SettingsSectionCard(title = "Optical Lens System") {
                                        Text("Active Camera Lens", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        availableLenses.forEach { lens ->
                                            val lensTypeStr = when (lens.lensType) {
                                                LensType.ULTRAWIDE -> "Ultra-Wide"
                                                LensType.TELEPHOTO, LensType.TELEPHOTO_3X -> "Telephoto"
                                                LensType.MACRO -> "Macro"
                                                LensType.FRONT -> "Front Selfie"
                                                else -> "Main Wide"
                                            }
                                            LightOptionRow(
                                                title = "${lens.displayName} (Camera ${lens.cameraId})",
                                                subtitle = "Focal length ${lens.focalLengthMm}mm · f/${lens.maxAperture} · $lensTypeStr",
                                                isSelected = selectedLens?.id == lens.id
                                            ) { onLensSelected(lens) }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = onForceDeepScan,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                                        ) {
                                            Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Deep Scan Auxiliary Lenses")
                                        }
                                    }
                                }
                            }

                            // 6. ZOOM
                            SettingsSubPage.ZOOM -> {
                                item {
                                    SettingsSectionCard(title = "Zoom Controls & Stabilization") {
                                        Text("Quick Zoom Presets", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f).forEach { zoom ->
                                                LightSelectPill("${if (zoom < 1f) ".5" else "${zoom.toInt()}x"}", (currentZoom - zoom).let { it >= -0.1f && it <= 0.1f }, { onZoomChange(zoom) }, Modifier.weight(1f))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Fine Zoom Scrubbing: %.1fx".format(currentZoom), fontSize = 13.sp, color = Color(0xFF4B5563))
                                        Slider(
                                            value = currentZoom,
                                            onValueChange = { onZoomChange(it) },
                                            valueRange = 0.5f..10.0f
                                        )
                                    }
                                }
                            }

                            // 7. FOCUS
                            SettingsSubPage.FOCUS -> {
                                item {
                                    SettingsSectionCard(title = "Focus & Tracking System") {
                                        LightToggleRow("Tap to Focus & Meter", "Locks focus reticle where you touch the preview", tapFocusConfig.isTapToFocusEnabled) {
                                            onTapFocusConfigChange(tapFocusConfig.copy(isTapToFocusEnabled = !tapFocusConfig.isTapToFocusEnabled))
                                        }
                                        LightToggleRow("AE/AF Lock on Hold", "Locks exposure and focus indefinitely on long-press", tapFocusConfig.isAeAfLockEnabled) {
                                            onTapFocusConfigChange(tapFocusConfig.copy(isAeAfLockEnabled = !tapFocusConfig.isAeAfLockEnabled))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Focus Mode", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FocusMode.entries.forEach { fm ->
                                                LightSelectPill(fm.title, focusMode == fm, { onFocusModeChange(fm) }, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            // 8. EXPOSURE
                            SettingsSubPage.EXPOSURE -> {
                                item {
                                    SettingsSectionCard(title = "Exposure & Lighting") {
                                        val evVal = exposureCompensation / 3.0f
                                        Text("Exposure Compensation (EV: ${if (evVal >= 0f) "+%.1f".format(evVal) else "%.1f".format(evVal)})", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Slider(
                                            value = exposureCompensation.toFloat(),
                                            onValueChange = { onExposureCompensationChange(it.toInt()) },
                                            valueRange = -12f..12f,
                                            steps = 23
                                        )
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Manual ISO Sensitivity", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            LightSelectPill("Auto", manualIso == null, { onManualIsoChange(null) }, Modifier.weight(1f))
                                            listOf(100, 200, 400, 800).forEach { iso ->
                                                LightSelectPill("$iso", manualIso == iso, { onManualIsoChange(iso) }, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            // 9. HDR
                            SettingsSubPage.HDR -> {
                                item {
                                    SettingsSectionCard(title = "HDR & Dynamic Range") {
                                        LightToggleRow("Auto HDR Capture", "Intelligently merges multi-bracket exposures in high-contrast scenes", isAutoHdrEnabled) {
                                            onAutoHdrToggle(!isAutoHdrEnabled)
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Night Mode Capture Duration", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(1, 2, 3, 5).forEach { sec ->
                                                LightSelectPill("${sec}s", nightConfig.durationSeconds == sec, { onNightConfigChange(nightConfig.copy(durationSeconds = sec)) }, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            // 10. AI
                            SettingsSubPage.AI -> {
                                item {
                                    SettingsSectionCard(title = "AI Smart Features & Bokeh") {
                                        LightToggleRow("AI Subject Auto-Framing", "Automatically centers and tracks recognized subjects", isAiAutoFramingEnabled) {
                                            onAiAutoFramingToggle(!isAiAutoFramingEnabled)
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Simulated Portrait Aperture", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("f/1.4", "f/2.0", "f/2.8", "f/4.0", "f/8.0").forEach { ap ->
                                                LightSelectPill(ap, portraitConfig.simulatedAperture == ap, { onPortraitConfigChange(portraitConfig.copy(simulatedAperture = ap)) }, Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            // 11. STABILIZATION
                            SettingsSubPage.STABILIZATION -> {
                                item {
                                    SettingsSectionCard(title = "Stabilization Engine") {
                                        LightToggleRow("Hybrid Stabilization Master", "Combines physical OIS with electronic gyroscope EIS", hybridStabilizationConfig.isHybridEnabled) {
                                            onHybridStabilizationChange(hybridStabilizationConfig.copy(isHybridEnabled = !hybridStabilizationConfig.isHybridEnabled))
                                        }
                                        LightToggleRow("Prefer Optical OIS", "Directs physical lens actuator for natural stabilization", hybridStabilizationConfig.isOisPreferred) {
                                            onHybridStabilizationChange(hybridStabilizationConfig.copy(isOisPreferred = !hybridStabilizationConfig.isOisPreferred))
                                        }
                                        LightToggleRow("Ultra Steady Action Mode", "Applies aggressive sensor crop for extreme sports", hybridStabilizationConfig.isUltraStabilizationEnabled) {
                                            onHybridStabilizationChange(hybridStabilizationConfig.copy(isUltraStabilizationEnabled = !hybridStabilizationConfig.isUltraStabilizationEnabled))
                                        }
                                    }
                                }
                            }

                            // 12. CODEC
                            SettingsSubPage.CODEC -> {
                                item {
                                    SettingsSectionCard(title = "Video & Audio Codecs") {
                                        Text("Video Compression Format", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        LightOptionRow("HEVC / H.265 (High Efficiency)", "Up to 50% smaller file sizes, enables 10-bit color recording", videoCodec == "HEVC") {
                                            onVideoCodecSelected("HEVC")
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LightOptionRow("H.264 / AVC (Most Compatible)", "Standard video format supported by all players and legacy devices", videoCodec == "H.264") {
                                            onVideoCodecSelected("H.264")
                                        }
                                    }
                                }
                            }

                            // 13. RESOLUTION_FPS
                            SettingsSubPage.RESOLUTION_FPS -> {
                                item {
                                    SettingsSectionCard(title = "Resolution & Framerate Matrix") {
                                        Text("Photo Resolution", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        capabilities.supportedPhotoResolutions.take(4).forEach { res ->
                                            LightOptionRow("${res.width} x ${res.height}", "%.1f MP (%s)".format(res.megapixels, res.aspectRatioLabel), selectedPhotoResolution == res) {
                                                onPhotoResolutionSelected(res)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Video Resolution", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        capabilities.supportedVideoResolutions.take(4).forEach { res ->
                                            LightOptionRow("${res.width} x ${res.height}", "${res.aspectRatioLabel} Video", selectedVideoResolution == res) {
                                                onVideoResolutionSelected(res)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }

                            // 14. AUDIO
                            SettingsSubPage.AUDIO -> {
                                item {
                                    SettingsSectionCard(title = "Audio Recording & Input") {
                                        LightToggleRow("Record Audio with Video", "Enables microphone track during video recording", isAudioEnabled) {
                                            onAudioToggle()
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        LightToggleRow("Wind Noise Reduction", "Filters low-frequency rumble outdoors", windNoiseReduction) {
                                            onWindNoiseReductionToggle(!windNoiseReduction)
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Audio Source", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("Camcorder Built-in", audioSource == "CAMCORDER", { onAudioSourceSelected("CAMCORDER") }, Modifier.weight(1f))
                                            LightSelectPill("Stereo Mic Array", audioSource == "MIC", { onAudioSourceSelected("MIC") }, Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            // 15. GRID
                            SettingsSubPage.GRID -> {
                                item {
                                    SettingsSectionCard(title = "Grid Lines & Composition") {
                                        Text("Viewfinder Framing Grid", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        GridType.entries.forEach { grid ->
                                            LightOptionRow(grid.title, "Framing reference lines", gridType == grid) {
                                                onGridTypeSelected(grid)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        LightToggleRow("Horizon Tilt Leveler", "Shows real-time horizon pitch indicator to prevent tilted shots", horizonLeveler) {
                                            onHorizonLevelerToggle(!horizonLeveler)
                                        }
                                    }
                                }
                            }

                            // 16. GESTURE
                            SettingsSubPage.GESTURE -> {
                                item {
                                    SettingsSectionCard(title = "Hardware Buttons & Gestures") {
                                        Text("Volume Key Action", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("Shutter", volumeKeyAction == "SHUTTER", { onVolumeKeyActionSelected("SHUTTER") }, Modifier.weight(1f))
                                            LightSelectPill("Zoom", volumeKeyAction == "ZOOM", { onVolumeKeyActionSelected("ZOOM") }, Modifier.weight(1f))
                                            LightSelectPill("Volume", volumeKeyAction == "VOLUME", { onVolumeKeyActionSelected("VOLUME") }, Modifier.weight(1f))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        Text("Double-Tap Screen Action", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("Flip Camera", doubleTapAction == "FLIP", { onDoubleTapActionSelected("FLIP") }, Modifier.weight(1f))
                                            LightSelectPill("2x Zoom", doubleTapAction == "ZOOM_2X", { onDoubleTapActionSelected("ZOOM_2X") }, Modifier.weight(1f))
                                            LightSelectPill("None", doubleTapAction == "NONE", { onDoubleTapActionSelected("NONE") }, Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            // 17. UI_CUSTOMIZATION
                            SettingsSubPage.UI_CUSTOMIZATION -> {
                                item {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xFF1E222D),
                                        border = BorderStroke(1.5.dp, Color(0xFF2563EB))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(42.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color(0xFF2563EB).copy(alpha = 0.2f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Smartphone,
                                                        contentDescription = null,
                                                        tint = Color(0xFF60A5FA),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Custom UI Studio",
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                    Text(
                                                        text = "Simulated device preview · Full text & icon styling · Upload any UI photo",
                                                        fontSize = 11.5.sp,
                                                        color = Color.White.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            Button(
                                                onClick = onOpenCustomUiStudio,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(44.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.DesignServices,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Open Custom UI Studio & Simulator",
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 13.5.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                item {
                                    SettingsSectionCard(title = "Camera UI Design Templates") {
                                        Text("Choose from 9 completely unique design languages:", fontSize = 12.sp, color = Color(0xFF6B7280))
                                        Spacer(modifier = Modifier.height(6.dp))
                                        UiTemplateType.entries.forEach { template ->
                                            val isSel = uiCustomizationState.selectedTemplate == template
                                            if (template == UiTemplateType.CUSTOM) {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isSel) Color(0xFFEFF6FF) else Color.White,
                                                    border = BorderStroke(
                                                        if (isSel) 1.5.dp else 1.dp,
                                                        if (isSel) Color(0xFF2563EB) else Color(0xFFE5E7EB)
                                                    )
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                onSelectTemplate(UiTemplateType.CUSTOM)
                                                                onOpenCustomUiStudio()
                                                            }
                                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(
                                                                    text = template.title,
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 14.sp,
                                                                    color = if (isSel) Color(0xFF1D4ED8) else Color(0xFF1F2937)
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = Color(0xFF2563EB).copy(alpha = 0.15f)
                                                                ) {
                                                                    Text(
                                                                        text = "STUDIO",
                                                                        fontSize = 9.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = Color(0xFF2563EB),
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                            Text(
                                                                text = template.subtitle,
                                                                fontSize = 11.5.sp,
                                                                color = Color(0xFF6B7280)
                                                            )
                                                        }
                                                        Button(
                                                            onClick = onOpenCustomUiStudio,
                                                            shape = RoundedCornerShape(8.dp),
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Text("Customize", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                                        }
                                                    }
                                                }
                                            } else {
                                                LightOptionRow(
                                                    title = template.title,
                                                    subtitle = template.subtitle,
                                                    isSelected = isSel
                                                ) {
                                                    onSelectTemplate(template)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }

                            // 18. PERFORMANCE
                            SettingsSubPage.PERFORMANCE -> {
                                item {
                                    SettingsSectionCard(title = "Performance & Battery Optimization") {
                                        Text("Viewfinder Preview Refresh Rate", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LightSelectPill("60 FPS (Smooth)", viewfinderFps == 60, { onViewfinderFpsSelected(60) }, Modifier.weight(1f))
                                            LightSelectPill("30 FPS (Battery Saver)", viewfinderFps == 30, { onViewfinderFpsSelected(30) }, Modifier.weight(1f))
                                        }
                                        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
                                        LightToggleRow("Thermal Throttling Protection", "Gracefully lowers sensor frame rate when phone heats up", thermalProtection) {
                                            onThermalProtectionToggle(!thermalProtection)
                                        }
                                    }
                                }
                            }

                            // 19. ADVANCED
                            SettingsSubPage.ADVANCED -> {
                                item {
                                    SettingsSectionCard(title = "Camera2 HAL Diagnostics & Reset") {
                                        val hwLevel = if (capabilities.supportsManualSensor) "Full Hardware Camera2 (Level 3 / Full)" else "Limited Hardware Camera2"
                                        LightSpecItem("Camera2 Hardware Level", hwLevel)
                                        LightSpecItem("Auxiliary Camera Lenses", "${availableLenses.size} detected")
                                        LightSpecItem("RAW Sensor Capture", if (capabilities.supportsRaw) "Supported" else "Not Available")
                                        LightSpecItem("Optical Stabilization (OIS)", if (capabilities.supportsOis) "Physical Gyro Present" else "Electronic Only")
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = onResetAllSettings,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                                        ) {
                                            Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Reset All Camera Settings to Defaults")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryTile(
    page: SettingsSubPage,
    summary: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("settings_tile_${page.name.lowercase()}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F0FE)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = page.title,
                        tint = Color(0xFF1A73E8),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = page.title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF111827)
                    )
                    Text(
                        text = summary,
                        fontSize = 11.5.sp,
                        color = Color(0xFF6B7280),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827)
            )
            content()
        }
    }
}

@Composable
private fun LightToggleRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color(0xFF1F2937) else Color(0xFF9CA3AF)
            )
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = if (enabled) Color(0xFF6B7280) else Color(0xFFD1D5DB),
                lineHeight = 15.sp
            )
        }

        Switch(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1A73E8),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD1D5DB)
            )
        )
    }
}

@Composable
private fun LightSelectPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFF1A73E8) else Color(0xFFF3F4F6),
        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFE5E7EB)),
        modifier = modifier
            .height(36.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFF374151)
            )
        }
    }
}

@Composable
private fun LightOptionRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFFE8F0FE) else Color(0xFFF9FAFB),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) Color(0xFF1A73E8) else Color(0xFFE5E7EB)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color(0xFF1A73E8) else Color(0xFF1F2937)
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF6B7280)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = Color(0xFF1A73E8),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LightSpecItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = Color(0xFF4B5563)
        )
        Text(
            text = value,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF111827)
        )
    }
}
