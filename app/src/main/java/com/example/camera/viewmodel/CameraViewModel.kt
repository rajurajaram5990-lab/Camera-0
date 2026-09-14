package com.example.camera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.data.CameraPreferences
import com.example.camera.engine.Camera2Engine
import com.example.camera.engine.PortraitProcessor
import com.example.camera.esrgan.RealEsrganState
import com.example.camera.model.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProControlTab(val label: String) {
    EXPOSURE("EV"),
    ISO("ISO"),
    SHUTTER("SEC"),
    WB("WB"),
    FOCUS("FOCUS"),
    TONE("TONE")
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val engine = Camera2Engine(application.applicationContext)
    private val portraitProcessor by lazy { PortraitProcessor(application.applicationContext) }
    private val preferences = CameraPreferences(application.applicationContext)

    val dollyZoomState: StateFlow<DollyZoomState> = engine.dollyZoomEngine.dollyState

    private val _nightConfig = MutableStateFlow(preferences.nightConfig)
    val nightConfig: StateFlow<NightConfig> = _nightConfig.asStateFlow()
    val nightProgress: StateFlow<NightCaptureProgress> = engine.nightProgress

    val hybridStabilizationConfig: StateFlow<HybridStabilizationConfig> = engine.hybridStabilizationConfig

    // Mode & Base State must be initialized before combined flows
    private val _cameraMode = MutableStateFlow(preferences.cameraMode)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _selectedAspectRatio = MutableStateFlow(CameraAspectRatio.RATIO_9_16)
    val selectedAspectRatio: StateFlow<CameraAspectRatio> = _selectedAspectRatio.asStateFlow()

    private val _tapFocusConfig = MutableStateFlow(preferences.tapFocusConfig)
    val tapFocusConfig: StateFlow<TapFocusConfig> = _tapFocusConfig.asStateFlow()

    val isRecordingVideo: StateFlow<Boolean> = engine.isRecordingVideo
    val videoDurationSeconds: StateFlow<Int> = engine.videoDurationSeconds

    // Portrait Mode Controls & Pipeline State
    private val _portraitConfig = MutableStateFlow(
        PortraitConfig(
            blurStrength = preferences.portraitBlurStrength,
            simulatedAperture = preferences.portraitAperture
        )
    )
    val portraitConfig: StateFlow<PortraitConfig> = _portraitConfig.asStateFlow()

    private val _portraitProcessingState = MutableStateFlow(PortraitProcessingState())
    val portraitProcessingState: StateFlow<PortraitProcessingState> = _portraitProcessingState.asStateFlow()

    // Photo Filter State
    private val _selectedPhotoFilter = MutableStateFlow(PhotoFilter.ORIGINAL)
    val selectedPhotoFilter: StateFlow<PhotoFilter> = _selectedPhotoFilter.asStateFlow()

    private val _isPhotoFilterBarOpen = MutableStateFlow(false)
    val isPhotoFilterBarOpen: StateFlow<Boolean> = _isPhotoFilterBarOpen.asStateFlow()

    // Portrait Style State
    private val _isPortraitStyleBarOpen = MutableStateFlow(false)
    val isPortraitStyleBarOpen: StateFlow<Boolean> = _isPortraitStyleBarOpen.asStateFlow()

    // UI Customization State
    private val _uiCustomizationState = MutableStateFlow(preferences.uiCustomizationState)
    val uiCustomizationState: StateFlow<UiCustomizationState> = _uiCustomizationState.asStateFlow()

    // Filtered lenses strictly adhering to current facing:
    // When on Back Camera -> ONLY back lenses (0.5x, 1x, 2x, etc.)
    // When on Front Camera -> ONLY front selfie lens
    val displayedLenses: StateFlow<List<LensInfo>> = combine(
        engine.availableLenses,
        engine.selectedLens
    ) { lenses, selected ->
        val currentFacing = selected?.facing ?: android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        lenses.filter { it.facing == currentFacing }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedLens: StateFlow<LensInfo?> = engine.selectedLens

    // Timer
    private val _timerMode = MutableStateFlow(preferences.timerMode)
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()

    private val _activeTimerCountdown = MutableStateFlow<Int?>(null)
    val activeTimerCountdown: StateFlow<Int?> = _activeTimerCountdown.asStateFlow()

    // Grid
    private val _gridType = MutableStateFlow(preferences.gridType)
    val gridType: StateFlow<GridType> = _gridType.asStateFlow()

    // Flash
    private val _flashMode = MutableStateFlow(preferences.flashMode)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    // Manual Pro controls drawer / bar
    private val _isManualProOpen = MutableStateFlow(false)
    val isManualProOpen: StateFlow<Boolean> = _isManualProOpen.asStateFlow()

    private val _activeProTab = MutableStateFlow(ProControlTab.EXPOSURE)
    val activeProTab: StateFlow<ProControlTab> = _activeProTab.asStateFlow()

    // Settings drawer & media viewer
    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _isVideoSettingsPanelOpen = MutableStateFlow(false)
    val isVideoSettingsPanelOpen: StateFlow<Boolean> = _isVideoSettingsPanelOpen.asStateFlow()

    private val _isMediaViewerOpen = MutableStateFlow(false)
    val isMediaViewerOpen: StateFlow<Boolean> = _isMediaViewerOpen.asStateFlow()

    // Focus indicator ring
    private val _focusRingPoint = MutableStateFlow<Offset?>(null)
    val focusRingPoint: StateFlow<Offset?> = _focusRingPoint.asStateFlow()

    // Mirror Selfie (Save selfie as previewed without flipping)
    private val _saveSelfieAsPreviewed = MutableStateFlow(preferences.saveSelfieAsPreviewed)
    val saveSelfieAsPreviewed: StateFlow<Boolean> = _saveSelfieAsPreviewed.asStateFlow()

    // Portrait Settings Window Open/Close
    private val _isPortraitSettingsOpen = MutableStateFlow(false)
    val isPortraitSettingsOpen: StateFlow<Boolean> = _isPortraitSettingsOpen.asStateFlow()

    // Video HDR State & Panel visibility
    val videoHdrState: StateFlow<VideoHdrState> = engine.videoHdrState
    private val _isVideoHdrPanelOpen = MutableStateFlow(false)
    val isVideoHdrPanelOpen: StateFlow<Boolean> = _isVideoHdrPanelOpen.asStateFlow()

    // Cinema Mode State & Panel visibility
    val cinemaConfig: StateFlow<CinemaConfig> = engine.cinemaConfig
    val cinemaCapabilities: StateFlow<CinemaHardwareCapabilities> = engine.cinemaCapabilities
    val capabilities: StateFlow<HardwareCapabilities> = engine.capabilities
    private val _isCinemaSettingsOpen = MutableStateFlow(false)
    val isCinemaSettingsOpen: StateFlow<Boolean> = _isCinemaSettingsOpen.asStateFlow()

    // Photo Megapixel Mode (12M, 50M, 100M, 200M Real-ESRGAN AI Super Resolution)
    private val _photoMegapixelMode = MutableStateFlow(preferences.photoMegapixelMode)
    val photoMegapixelMode: StateFlow<PhotoMegapixelMode> = _photoMegapixelMode.asStateFlow()

    // Real-ESRGAN AI Super Resolution State
    private val _realEsrganState = MutableStateFlow(RealEsrganState())
    val realEsrganState: StateFlow<RealEsrganState> = _realEsrganState.asStateFlow()

    // Refocus Photo Mode
    private val _isRefocusPhotoEnabled = MutableStateFlow(preferences.isRefocusPhotoEnabled)
    val isRefocusPhotoEnabled: StateFlow<Boolean> = _isRefocusPhotoEnabled.asStateFlow()

    // AI Zoom (Deep Burst Super-Resolution)
    private val _isAiZoomEnabled = MutableStateFlow(preferences.isAiZoomEnabled)
    val isAiZoomEnabled: StateFlow<Boolean> = _isAiZoomEnabled.asStateFlow()

    private val _aiZoomQuality = MutableStateFlow(preferences.aiZoomQuality)
    val aiZoomQuality: StateFlow<com.example.camera.dbsr.AiZoomQuality> = _aiZoomQuality.asStateFlow()

    val isAiZoomProcessing: StateFlow<Boolean> = engine.isAiZoomProcessing
    val aiZoomProgress: StateFlow<Float> = engine.aiZoomProgress

    fun setAiZoomEnabled(enabled: Boolean) {
        _isAiZoomEnabled.value = enabled
        preferences.isAiZoomEnabled = enabled
        engine.isAiZoomEnabled = enabled
        if (enabled) {
            viewModelScope.launch(Dispatchers.Default) {
                engine.dbsrEngine.preload()
            }
            showToast("AI Zoom (DBSR): ON")
        } else {
            showToast("AI Zoom (DBSR): OFF")
        }
    }

    fun setAiZoomQuality(quality: com.example.camera.dbsr.AiZoomQuality) {
        _aiZoomQuality.value = quality
        preferences.aiZoomQuality = quality
        engine.aiZoomQuality = quality
        showToast("AI Zoom Quality: ${quality.label}")
    }

    fun setRefocusPhotoEnabled(enabled: Boolean) {
        _isRefocusPhotoEnabled.value = enabled
        preferences.isRefocusPhotoEnabled = enabled
        engine.isRefocusPhotoEnabled = enabled
        if (enabled) {
            showToast("Refocus Photo: ON")
        } else {
            showToast("Refocus Photo: OFF")
        }
    }

    fun setPhotoMegapixelMode(mode: PhotoMegapixelMode) {
        _photoMegapixelMode.value = mode
        preferences.photoMegapixelMode = mode
        engine.photoMegapixelMode = mode
        if (mode.isSuperResolution) {
            showToast("${mode.label} Real-ESRGAN AI Super Resolution")
        } else {
            showToast("12M Standard Mode")
        }
    }

    fun togglePhotoMegapixelMode() {
        val next = when (_photoMegapixelMode.value) {
            PhotoMegapixelMode.M12 -> PhotoMegapixelMode.M50
            PhotoMegapixelMode.M50 -> PhotoMegapixelMode.M100
            PhotoMegapixelMode.M100 -> PhotoMegapixelMode.M200
            PhotoMegapixelMode.M200 -> PhotoMegapixelMode.M12
        }
        _photoMegapixelMode.value = next
        preferences.photoMegapixelMode = next
        engine.photoMegapixelMode = next
        if (next.isSuperResolution) {
            showToast("${next.label} Real-ESRGAN AI Super Resolution")
        } else {
            showToast("12M Standard Mode")
        }
    }

    // More Modes Drawer visibility
    private val _isMoreModesOpen = MutableStateFlow(false)
    val isMoreModesOpen: StateFlow<Boolean> = _isMoreModesOpen.asStateFlow()

    fun setMoreModesOpen(isOpen: Boolean) {
        _isMoreModesOpen.value = isOpen
    }

    fun toggleMoreModes() {
        _isMoreModesOpen.value = !_isMoreModesOpen.value
    }

    fun setCinemaSettingsOpen(isOpen: Boolean) {
        _isCinemaSettingsOpen.value = isOpen
    }

    fun toggleCinemaSettings() {
        _isCinemaSettingsOpen.value = !_isCinemaSettingsOpen.value
    }

    fun updateCinemaConfig(config: CinemaConfig) {
        engine.setCinemaConfig(config)
        preferences.saveCinemaConfig(config)
    }

    // Background Sequential Queue for Portrait Processing
    // Strictly queues portrait captures sequentially to prevent duplicate processing,
    // memory spikes, and crashes during rapid multi-photo captures.
    private val portraitProcessingChannel = Channel<Pair<Bitmap, PortraitConfig>>(capacity = 10)

    // Toast/Feedback banner
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Parameters
    private val _exposureCompensation = MutableStateFlow(0)
    val exposureCompensation: StateFlow<Int> = _exposureCompensation.asStateFlow()

    private val _manualIso = MutableStateFlow<Int?>(null)
    val manualIso: StateFlow<Int?> = _manualIso.asStateFlow()

    private val _manualShutterSpeedNs = MutableStateFlow<Long?>(null)
    val manualShutterSpeedNs: StateFlow<Long?> = _manualShutterSpeedNs.asStateFlow()

    private val _whiteBalance = MutableStateFlow(preferences.whiteBalance)
    val whiteBalance: StateFlow<WhiteBalanceMode> = _whiteBalance.asStateFlow()

    private val _focusMode = MutableStateFlow(preferences.focusMode)
    val focusMode: StateFlow<FocusMode> = _focusMode.asStateFlow()

    private val _manualFocusDistance = MutableStateFlow(0f)
    val manualFocusDistance: StateFlow<Float> = _manualFocusDistance.asStateFlow()

    val isAeLocked: StateFlow<Boolean> = engine.isAeLockedFlow
    val isAfLocked: StateFlow<Boolean> = engine.isAfLockedFlow

    val isCameraInitialized: StateFlow<Boolean> = engine.isCameraInitialized
    val cameraInitError: StateFlow<String?> = engine.cameraInitError

    fun safeInitializeCamera(onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.Default) {
            engine.safeInitializeCamera { success, error ->
                viewModelScope.launch(Dispatchers.Main) {
                    onResult(success, error)
                }
            }
        }
    }

    private val _isRawCaptureEnabled = MutableStateFlow(preferences.isRawEnabled)
    val isRawCaptureEnabled: StateFlow<Boolean> = _isRawCaptureEnabled.asStateFlow()

    private val _isVideoStabilizationEnabled = MutableStateFlow(preferences.isVideoStabilizationEnabled)
    val isVideoStabilizationEnabled: StateFlow<Boolean> = _isVideoStabilizationEnabled.asStateFlow()

    private val _videoBitrateOption = MutableStateFlow(preferences.videoBitrate)
    val videoBitrateOption: StateFlow<VideoBitrateOption> = _videoBitrateOption.asStateFlow()

    private val _videoFps = MutableStateFlow(preferences.videoFps)
    val videoFps: StateFlow<Int> = _videoFps.asStateFlow()

    private val _viewfinderResolution = MutableStateFlow(preferences.viewfinderResolution)
    val viewfinderResolution: StateFlow<ViewfinderResolution> = _viewfinderResolution.asStateFlow()

    private val _colorProfile = MutableStateFlow(preferences.colorProfile)
    val colorProfile: StateFlow<ColorProfile> = _colorProfile.asStateFlow()

    private val _isAudioEnabled = MutableStateFlow(preferences.isAudioEnabled)
    val isAudioEnabled: StateFlow<Boolean> = _isAudioEnabled.asStateFlow()

    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoom: StateFlow<Float> = _currentZoom.asStateFlow()

    // Active Video Quality (4K 30, 4K 60, 1080p 30, 1080p 60, 720p 30)
    val currentVideoQuality: StateFlow<VideoQualityOption> = combine(
        engine.selectedVideoResolution,
        _videoFps
    ) { res, fps ->
        when {
            res?.width == 3840 && fps == 60 -> VideoQualityOption.UHD_4K_60
            res?.width == 3840 -> VideoQualityOption.UHD_4K_30
            res?.width == 1920 && fps == 60 -> VideoQualityOption.FHD_1080_60
            res?.width == 1920 -> VideoQualityOption.FHD_1080_30
            res?.width == 1280 -> VideoQualityOption.HD_720_30
            else -> VideoQualityOption.UHD_4K_30
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VideoQualityOption.UHD_4K_30)

    private var timerJob: Job? = null
    private var focusDismissJob: Job? = null
    private var toastDismissJob: Job? = null

    init {
        // Apply restored preferences into engine
        engine.flashMode = preferences.flashMode
        engine.isRawCaptureEnabled = preferences.isRawEnabled
        engine.isVideoStabilizationEnabled = preferences.isVideoStabilizationEnabled
        engine.videoBitrateOption = preferences.videoBitrate
        engine.videoFps = preferences.videoFps
        engine.colorProfile = preferences.colorProfile
        engine.isAudioEnabled = preferences.isAudioEnabled
        engine.whiteBalanceMode = preferences.whiteBalance
        engine.focusMode = preferences.focusMode
        engine.saveSelfieAsPreviewed = preferences.saveSelfieAsPreviewed
        engine.viewfinderResolution = preferences.viewfinderResolution
        engine.photoMegapixelMode = preferences.photoMegapixelMode
        engine.isRefocusPhotoEnabled = preferences.isRefocusPhotoEnabled
        engine.isAiZoomEnabled = preferences.isAiZoomEnabled
        engine.aiZoomQuality = preferences.aiZoomQuality
        if (preferences.isAiZoomEnabled) {
            viewModelScope.launch(Dispatchers.Default) {
                engine.dbsrEngine.preload()
            }
        }
        // Video HDR system removed: permanently OFF
        engine.setVideoHdrMode(VideoHdrMode.OFF)
        engine.setMode(preferences.cameraMode)
        engine.restoreInitialVideoResolution(CameraResolution(preferences.videoWidth, preferences.videoHeight))
        engine.setCinemaConfig(preferences.getCinemaConfig())
        engine.updateHybridStabilizationConfig(preferences.hybridStabilizationConfig)

        viewModelScope.launch {
            engine.currentZoomState.collect { zoom ->
                _currentZoom.value = zoom
            }
        }

        // Sequential background portrait processor
        // Processes portrait jobs safely one by one in the background without UI blocking,
        // memory spikes, or duplicate processing crashes.
        viewModelScope.launch(Dispatchers.Default) {
            for ((bitmap, config) in portraitProcessingChannel) {
                try {
                    val uri = portraitProcessor.processAndSavePortrait(
                        orientedBitmap = bitmap,
                        config = config
                    )
                    if (uri != null) {
                        withContext(Dispatchers.Main) {
                            showToast("Portrait saved to DCIM/Camera")
                            engine.updateStorageStats()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            showToast("Portrait processing failed")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("CameraViewModel", "Error in background portrait processing", t)
                } finally {
                    try {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    fun setPortraitSettingsOpen(isOpen: Boolean) {
        _isPortraitSettingsOpen.value = isOpen
    }

    fun toggleSaveSelfieAsPreviewed() {
        val next = !_saveSelfieAsPreviewed.value
        _saveSelfieAsPreviewed.value = next
        preferences.saveSelfieAsPreviewed = next
        engine.saveSelfieAsPreviewed = next
        showToast(if (next) "Save selfie as previewed: ON" else "Save selfie as previewed: OFF")
    }

    fun setSaveSelfieAsPreviewed(enabled: Boolean) {
        _saveSelfieAsPreviewed.value = enabled
        preferences.saveSelfieAsPreviewed = enabled
        engine.saveSelfieAsPreviewed = enabled
    }

    // Video HDR Controls
    fun setVideoHdrMode(mode: VideoHdrMode) {
        preferences.videoHdrMode = mode
        engine.setVideoHdrMode(mode)
        showToast("Video HDR: ${mode.label}")
    }

    fun cycleVideoHdrMode() {
        val next = when (videoHdrState.value.mode) {
            VideoHdrMode.OFF -> VideoHdrMode.AUTO
            VideoHdrMode.AUTO -> VideoHdrMode.MANUAL
            VideoHdrMode.MANUAL -> VideoHdrMode.OFF
        }
        setVideoHdrMode(next)
    }

    fun setVideoHdrManualIntensity(intensity: Int) {
        preferences.videoHdrManualIntensity = intensity
        engine.setVideoHdrManualIntensity(intensity)
    }

    fun setVideoHdrManualShadows(value: Int) {
        preferences.videoHdrManualShadows = value
        engine.setVideoHdrManualShadows(value)
    }

    fun setVideoHdrManualHighlights(value: Int) {
        preferences.videoHdrManualHighlights = value
        engine.setVideoHdrManualHighlights(value)
    }

    fun setVideoHdrManualContrast(value: Int) {
        preferences.videoHdrManualContrast = value
        engine.setVideoHdrManualContrast(value)
    }

    fun setVideoHdrManualExposure(value: Int) {
        preferences.videoHdrManualExposure = value
        engine.setVideoHdrManualExposure(value)
    }

    fun setVideoHdrManualBlackLevel(value: Int) {
        preferences.videoHdrManualBlackLevel = value
        engine.setVideoHdrManualBlackLevel(value)
    }

    fun setVideoHdrManualMidtones(value: Int) {
        preferences.videoHdrManualMidtones = value
        engine.setVideoHdrManualMidtones(value)
    }

    fun setVideoHdrManualSaturation(value: Int) {
        preferences.videoHdrManualSaturation = value
        engine.setVideoHdrManualSaturation(value)
    }

    fun setVideoHdrPanelOpen(isOpen: Boolean) {
        _isVideoHdrPanelOpen.value = isOpen
    }

    fun toggleVideoHdrPanel() {
        _isVideoHdrPanelOpen.value = !_isVideoHdrPanelOpen.value
    }

    fun setCameraMode(mode: CameraMode) {
        val previousMode = _cameraMode.value
        _cameraMode.value = mode
        preferences.cameraMode = mode

        // Apply true 9:16 aspect ratio for Video and Cinema modes before session reconfiguration
        if (mode == CameraMode.VIDEO || mode == CameraMode.CINEMA || mode == CameraMode.DOLLY_ZOOM) {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_9_16
            engine.setPreviewAspectRatio(16f / 9f)
        } else if (mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT || mode == CameraMode.NIGHT || mode == CameraMode.MORE) {
            _selectedAspectRatio.value = CameraAspectRatio.RATIO_4_3
            engine.setPreviewAspectRatio(4f / 3f)
        }

        if (mode == CameraMode.AI_SUBJECT_TRACKING) {
            engine.closeCamera()
        } else {
            if (previousMode == CameraMode.AI_SUBJECT_TRACKING) {
                engine.startCamera()
            }
            engine.setMode(mode)
        }

        _isCinemaSettingsOpen.value = false
        if (mode == CameraMode.MORE) {
            _isMoreModesOpen.value = true
        }
    }

    fun selectLens(lens: LensInfo) {
        engine.selectLens(lens)
        preferences.lastFacing = lens.facing
        val lensDesc = when (lens.lensType) {
            LensType.ULTRAWIDE -> "0.5x Ultra-Wide"
            LensType.WIDE -> "1x Main"
            LensType.TELEPHOTO -> "2x Telephoto"
            LensType.TELEPHOTO_3X -> "3x Telephoto"
            LensType.MACRO -> "Macro"
            LensType.FRONT -> "Front Selfie"
        }
        showToast("Switched to $lensDesc Lens")
    }

    fun forceDeepScanLenses() {
        val count = engine.detectHardwareLenses(forceDeepScan = true)
        showToast("Deep scan found $count hardware & aux lenses")
    }

    fun toggleCameraFacing() {
        val currentLens = engine.selectedLens.value ?: return
        val targetFacing = if (currentLens.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }
        val targetLens = engine.availableLenses.value.firstOrNull {
            it.facing == targetFacing && (it.lensType == LensType.WIDE || it.lensType == LensType.FRONT) && !it.isZoomPreset
        } ?: engine.availableLenses.value.firstOrNull { it.facing == targetFacing }

        if (targetLens != null) {
            engine.selectLens(targetLens)
            preferences.lastFacing = targetFacing
            val label = if (targetFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "Front Camera" else "Rear Camera"
            showToast("Switched to $label")
        }
    }

    fun cycleFlashMode() {
        val nextMode = when (_flashMode.value) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        _flashMode.value = nextMode
        preferences.flashMode = nextMode
        engine.flashMode = nextMode
        engine.updatePreviewSettings()
        showToast("Flash: ${nextMode.title}")
    }

    fun cycleTimerMode() {
        val nextMode = when (_timerMode.value) {
            TimerMode.OFF -> TimerMode.SEC_3
            TimerMode.SEC_3 -> TimerMode.SEC_5
            TimerMode.SEC_5 -> TimerMode.SEC_10
            TimerMode.SEC_10 -> TimerMode.OFF
        }
        _timerMode.value = nextMode
        preferences.timerMode = nextMode
        showToast("Timer: ${nextMode.label}")
    }

    fun setGridType(type: GridType) {
        _gridType.value = type
        preferences.gridType = type
    }

    fun cycleGridType() {
        val nextGrid = when (_gridType.value) {
            GridType.NONE -> GridType.THIRDS
            GridType.THIRDS -> GridType.GOLDEN
            GridType.GOLDEN -> GridType.SQUARE
            GridType.SQUARE -> GridType.LEVEL
            GridType.LEVEL -> GridType.NONE
        }
        _gridType.value = nextGrid
        preferences.gridType = nextGrid
        showToast("Grid: ${nextGrid.title}")
    }

    fun toggleRawCapture() {
        val caps = engine.capabilities.value
        if (!caps.supportsRaw) {
            showToast("RAW format not supported by this sensor")
            return
        }
        val next = !_isRawCaptureEnabled.value
        _isRawCaptureEnabled.value = next
        preferences.isRawEnabled = next
        engine.isRawCaptureEnabled = next
        engine.restartCamera()
        showToast(if (next) "RAW (DNG + JPEG) Enabled" else "RAW Disabled")
    }

    fun cycleVideoQuality() {
        val supportedQualities = listOf(
            VideoQualityOption.UHD_4K_30,
            VideoQualityOption.UHD_4K_60,
            VideoQualityOption.FHD_1080_30,
            VideoQualityOption.FHD_1080_60,
            VideoQualityOption.HD_720_30
        )
        val currentIndex = supportedQualities.indexOf(currentVideoQuality.value).let { if (it >= 0) it else 0 }
        val nextQuality = supportedQualities[(currentIndex + 1) % supportedQualities.size]
        setVideoQuality(nextQuality)
    }

    fun setVideoQuality(quality: VideoQualityOption) {
        engine.selectVideoResolution(quality.resolution)
        engine.videoFps = quality.fps
        _videoFps.value = quality.fps
        preferences.videoWidth = quality.width
        preferences.videoHeight = quality.height
        preferences.videoFps = quality.fps
        showToast("Video Quality: ${quality.fullLabel}")
    }

    fun toggleManualPro() {
        _isManualProOpen.value = !_isManualProOpen.value
    }

    fun setManualProOpen(open: Boolean) {
        _isManualProOpen.value = open
    }

    fun setActiveProTab(tab: ProControlTab) {
        _activeProTab.value = tab
    }

    fun setExposureCompensation(value: Int) {
        _exposureCompensation.value = value
        engine.exposureCompensationIndex = value
        engine.updatePreviewSettings()
    }

    fun setManualIso(iso: Int?) {
        _manualIso.value = iso
        engine.manualIso = iso
        engine.updatePreviewSettings()
    }

    fun setManualShutterSpeedNs(ns: Long?) {
        _manualShutterSpeedNs.value = ns
        engine.manualExposureTimeNs = ns
        engine.updatePreviewSettings()
    }

    fun setWhiteBalance(wb: WhiteBalanceMode) {
        _whiteBalance.value = wb
        preferences.whiteBalance = wb
        engine.whiteBalanceMode = wb
        engine.updatePreviewSettings()
        showToast("WB: ${wb.title}")
    }

    fun setFocusMode(mode: FocusMode) {
        _focusMode.value = mode
        preferences.focusMode = mode
        engine.focusMode = mode
        engine.updatePreviewSettings()
        showToast("Focus: ${mode.title}")
    }

    fun setManualFocusDistance(distance: Float) {
        _manualFocusDistance.value = distance
        engine.manualFocusDistance = distance
        engine.updatePreviewSettings()
    }

    fun toggleAeLock() {
        val next = !engine.isAeLockedFlow.value
        engine.isAeLocked = next
        engine.updatePreviewSettings()
        showToast(if (next) "Exposure Locked" else "Exposure Unlocked")
    }

    fun toggleAfLock() {
        val next = !engine.isAfLockedFlow.value
        engine.isAfLocked = next
        engine.updatePreviewSettings()
        showToast(if (next) "Focus Locked" else "Focus Unlocked")
    }

    fun setZoom(zoom: Float, isPresetTap: Boolean = false) {
        val clamped = zoom.coerceIn(0.5f, 10.0f)
        _currentZoom.value = clamped
        engine.setZoom(clamped, isPresetTap)
    }

    fun setVideoStabilization(enabled: Boolean) {
        val caps = engine.capabilities.value
        if (!caps.supportsEis && !caps.supportsOis) {
            showToast("Stabilization not supported by hardware")
            return
        }
        _isVideoStabilizationEnabled.value = enabled
        preferences.isVideoStabilizationEnabled = enabled
        engine.isVideoStabilizationEnabled = enabled
        engine.updatePreviewSettings()
        showToast(if (enabled) "Stabilization Enabled" else "Stabilization Disabled")
    }

    fun setVideoBitrate(bitrate: VideoBitrateOption) {
        _videoBitrateOption.value = bitrate
        preferences.videoBitrate = bitrate
        engine.videoBitrateOption = bitrate
        showToast("Bitrate: ${bitrate.title}")
    }

    fun setVideoFps(fps: Int) {
        _videoFps.value = fps
        preferences.videoFps = fps
        engine.videoFps = fps
        showToast("Frame Rate: ${fps} FPS")
    }

    fun setColorProfile(profile: ColorProfile) {
        _colorProfile.value = profile
        preferences.colorProfile = profile
        engine.colorProfile = profile
        engine.updatePreviewSettings()
        showToast("Profile: ${profile.title}")
    }

    fun toggleAudio() {
        val next = !_isAudioEnabled.value
        _isAudioEnabled.value = next
        preferences.isAudioEnabled = next
        engine.isAudioEnabled = next
        showToast(if (next) "Audio Recording On" else "Audio Muted")
    }

    fun selectPhotoResolution(res: CameraResolution) {
        engine.selectPhotoResolution(res)
        showToast("Photo Resolution: ${res.displayLabel}")
    }

    fun selectVideoResolution(res: CameraResolution) {
        engine.selectVideoResolution(res)
        preferences.videoWidth = res.width
        preferences.videoHeight = res.height
        showToast("Video Resolution: ${res.displayLabel}")
    }

    fun setViewfinderResolution(res: ViewfinderResolution) {
        _viewfinderResolution.value = res
        preferences.viewfinderResolution = res
        engine.applyViewfinderResolution(res)
        showToast("Viewfinder: ${res.label}")
    }

    fun setSettingsOpen(open: Boolean) {
        _isSettingsOpen.value = open
    }

    fun setVideoSettingsPanelOpen(open: Boolean) {
        _isVideoSettingsPanelOpen.value = open
    }

    fun toggleVideoSettingsPanel() {
        _isVideoSettingsPanelOpen.value = !_isVideoSettingsPanelOpen.value
    }

    fun setMediaViewerOpen(open: Boolean) {
        _isMediaViewerOpen.value = open
    }

    fun onTapToFocus(point: Offset, normX: Float, normY: Float, isLock: Boolean = false) {
        _focusRingPoint.value = point
        engine.triggerFocusAndMeter(normX, normY, isLock)

        if (!isLock) {
            focusDismissJob?.cancel()
            focusDismissJob = viewModelScope.launch {
                delay(2400)
                _focusRingPoint.value = null
            }
        }
    }

    fun toggleAeAfLock() {
        engine.toggleAeAfLock()
        val locked = engine.isAeLockedFlow.value
        showToast(if (locked) "AE/AF LOCKED" else "AE/AF UNLOCKED")
    }

    fun setNightConfig(config: NightConfig) {
        _nightConfig.value = config
        preferences.nightConfig = config
    }

    fun setHybridStabilizationConfig(config: HybridStabilizationConfig) {
        engine.updateHybridStabilizationConfig(config)
        preferences.hybridStabilizationConfig = config
    }

    fun toggleUltraStabilization() {
        val current = hybridStabilizationConfig.value
        val nextState = !current.isUltraStabilizationEnabled
        val updated = current.copy(isUltraStabilizationEnabled = nextState)
        setHybridStabilizationConfig(updated)
        showToast(if (nextState) "Ultra Stabilization: Active" else "Ultra Stabilization: Off")
    }

    fun setTapFocusConfig(config: TapFocusConfig) {
        _tapFocusConfig.value = config
        preferences.tapFocusConfig = config
    }

    fun calibrateDollyZoom() {
        engine.calibrateDollyZoom()
        showToast("Dolly Subject Calibrated")
    }

    fun resetDollyZoom() {
        engine.resetDollyZoom()
        showToast("Dolly Zoom Reset")
    }

    fun lockDollySubjectAt(x: Float, y: Float) {
        engine.lockDollySubjectAt(x, y)
        showToast("Subject Locked for Dolly Zoom")
    }

    fun triggerNightCapture() {
        if (engine.isCapturing.value) return
        val config = _nightConfig.value
        engine.takeNightPhoto(
            durationSeconds = config.durationSeconds,
            isAntiGhosting = config.antiGhostingEnabled,
            noiseSuppression = config.noiseSuppression,
            shadowLift = config.shadowLift,
            onProgress = {},
            onComplete = { uri ->
                if (uri != null) {
                    showToast("Night photo captured")
                } else {
                    showToast("Night capture failed")
                }
            }
        )
    }

    fun setPortraitBlurStrength(strength: Float) {
        _portraitConfig.update { it.copy(blurStrength = strength) }
        preferences.portraitBlurStrength = strength
    }

    fun setPortraitAperture(aperture: String) {
        _portraitConfig.update { it.copy(simulatedAperture = aperture) }
        preferences.portraitAperture = aperture
        showToast("Aperture: $aperture")
    }

    fun setPortraitBokehStyle(style: BokehStyle) {
        _portraitConfig.update { it.copy(bokehStyle = style) }
        showToast("Bokeh: ${style.label}")
    }

    fun togglePortraitFaceEnhancement() {
        val next = !_portraitConfig.value.faceEnhancement
        _portraitConfig.update { it.copy(faceEnhancement = next) }
        showToast("Face Enhancement: ${if (next) "ON" else "OFF"}")
    }

    fun togglePortraitSkinTone() {
        val next = !_portraitConfig.value.skinToneCorrection
        _portraitConfig.update { it.copy(skinToneCorrection = next) }
        showToast("Skin Tone Correction: ${if (next) "ON" else "OFF"}")
    }

    fun setSelectedPhotoFilter(filter: PhotoFilter) {
        _selectedPhotoFilter.value = filter
        engine.selectedPhotoFilter = filter
        showToast("Filter: ${filter.displayName}")
    }

    fun togglePhotoFilterBar() {
        _isPhotoFilterBarOpen.value = !_isPhotoFilterBarOpen.value
    }

    fun setPhotoFilterBarOpen(open: Boolean) {
        _isPhotoFilterBarOpen.value = open
    }

    fun setSelectedPortraitStyle(style: PortraitStyle) {
        _portraitConfig.update { it.copy(selectedStyle = style) }
        showToast("Portrait Style: ${style.displayName}")
    }

    fun togglePortraitStyleBar() {
        _isPortraitStyleBarOpen.value = !_isPortraitStyleBarOpen.value
    }

    fun setPortraitStyleBarOpen(open: Boolean) {
        _isPortraitStyleBarOpen.value = open
    }

    fun onMainActionButtonClick() {
        when (_cameraMode.value) {
            CameraMode.PHOTO, CameraMode.MORE, CameraMode.AI_SUBJECT_TRACKING -> triggerPhotoCapture()
            CameraMode.PORTRAIT -> triggerPortraitCapture()
            CameraMode.VIDEO, CameraMode.CINEMA, CameraMode.DOLLY_ZOOM -> triggerVideoCapture()
            CameraMode.NIGHT -> triggerNightCapture()
        }
    }

    private fun triggerPhotoCapture() {
        if (engine.isCapturing.value) return

        val timerSeconds = _timerMode.value.seconds
        if (timerSeconds > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _activeTimerCountdown.value = remaining
                    delay(1000)
                }
                _activeTimerCountdown.value = null
                executePhotoCapture()
            }
        } else {
            executePhotoCapture()
        }
    }

    private fun executePhotoCapture() {
        val mode = _photoMegapixelMode.value
        val isSuperRes = mode.isSuperResolution
        if (isSuperRes) {
            showToast("Capturing ${mode.label} sensor frame...")
        }
        engine.takePhoto { uri ->
            if (uri != null) {
                if (isSuperRes) {
                    val pending = engine.lastCapturedMedia.value
                    if (pending != null && pending.isPendingAiProcessing) {
                        startRealEsrganProcessing(pending)
                    } else {
                        showToast("${mode.label} captured! Processing Real-ESRGAN...")
                    }
                } else {
                    showToast("Saved to DCIM/Camera")
                }
            } else {
                showToast("Failed to save photo")
            }
        }
    }

    /**
     * Starts the official xinntao/Real-ESRGAN AI Super Resolution processing pipeline
     * on the pending captured sensor frame.
     */
    fun startRealEsrganProcessing(media: CapturedMedia) {
        if (_realEsrganState.value.isProcessing) return
        val filePath = media.pendingRawFilePath ?: return
        val file = File(filePath)
        if (!file.exists()) return

        val mode = media.aiResolutionMode ?: _photoMegapixelMode.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _realEsrganState.value = RealEsrganState(
                    isProcessing = true,
                    progress = 0.05f,
                    stageMessage = "Loading high-precision sensor frame...",
                    activeMode = mode,
                    isGpuActive = true
                )

                val options = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val sourceBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                if (sourceBitmap == null) {
                    _realEsrganState.value = _realEsrganState.value.copy(
                        isProcessing = false,
                        error = "Could not decode source sensor frame"
                    )
                    return@launch
                }

                val finalUri = engine.realEsrganEngine.processAndSaveRealEsrgan(
                    sourceBitmap = sourceBitmap,
                    targetMode = mode,
                    onProgress = { prog, stage, info ->
                        _realEsrganState.value = _realEsrganState.value.copy(
                            progress = prog,
                            stageMessage = stage,
                            loadedModelName = info.modelName,
                            backendName = info.backendName,
                            inputResolutionText = info.inputRes,
                            targetResolutionText = info.targetRes,
                            scaleFactorText = info.scaleFactor,
                            tileCountText = "${info.completedTiles}/${info.totalTiles}",
                            isGpuActive = info.backendName.contains("GPU")
                        )
                    }
                )

                sourceBitmap.recycle()
                try { file.delete() } catch (ignored: Exception) {}

                val updatedMedia = media.copy(
                    uri = finalUri,
                    displayName = "Real-ESRGAN ${mode.label}.jpg",
                    isPendingAiProcessing = false,
                    pendingRawFilePath = null
                )
                engine.setLastCapturedMedia(updatedMedia)
                _realEsrganState.value = _realEsrganState.value.copy(
                    isProcessing = false,
                    progress = 1.0f,
                    stageMessage = "Super Resolution complete!",
                    finalUri = finalUri
                )
                withContext(Dispatchers.Main) {
                    _isMediaViewerOpen.value = true
                    showToast("${mode.label} Real-ESRGAN photo saved to Gallery")
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Real-ESRGAN processing error", e)
                _realEsrganState.value = _realEsrganState.value.copy(
                    isProcessing = false,
                    error = e.message ?: "Real-ESRGAN processing failed"
                )
            }
        }
    }

    fun resetRealEsrganState() {
        _realEsrganState.value = RealEsrganState()
    }

    private fun triggerPortraitCapture() {
        if (engine.isCapturing.value) return

        val timerSeconds = _timerMode.value.seconds
        if (timerSeconds > 0) {
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _activeTimerCountdown.value = remaining
                    delay(1000)
                }
                _activeTimerCountdown.value = null
                executePortraitCapture()
            }
        } else {
            executePortraitCapture()
        }
    }

    private fun executePortraitCapture() {
        engine.captureStillBitmap { capturedBitmap ->
            if (capturedBitmap == null) {
                showToast("Portrait capture failed")
                return@captureStillBitmap
            }

            // Immediately return to camera viewfinder and process in the background.
            // No progress or loading UI is shown.
            val config = _portraitConfig.value
            val result = portraitProcessingChannel.trySend(Pair(capturedBitmap, config))
            if (!result.isSuccess) {
                viewModelScope.launch(Dispatchers.Default) {
                    portraitProcessingChannel.send(Pair(capturedBitmap, config))
                }
            }
        }
    }

    private fun triggerVideoCapture() {
        if (engine.isRecordingVideo.value) {
            engine.stopVideoRecording()
            showToast("Video saved to DCIM/Camera")
        } else {
            engine.startVideoRecording { error ->
                showToast("Recording error: $error")
            }
        }
    }

    // --- Camera UI Customization & Templates ---

    fun selectUiTemplate(template: UiTemplateType) {
        val templateConfig = CameraUiTemplates.getTemplateConfig(template)
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = template,
            globalConfig = templateConfig
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Switched to ${template.title}")
    }

    fun updateGlobalLayoutConfig(config: ModeLayoutConfig) {
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = UiTemplateType.CUSTOM,
            globalConfig = config
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
    }

    fun updateModeLayoutConfig(mode: CameraMode, config: ModeLayoutConfig) {
        val currentModes = _uiCustomizationState.value.modeSpecificConfigs.toMutableMap()
        currentModes[mode] = config
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = UiTemplateType.CUSTOM,
            modeSpecificConfigs = currentModes
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
    }

    fun resetModeLayoutToGlobal(mode: CameraMode) {
        val currentModes = _uiCustomizationState.value.modeSpecificConfigs.toMutableMap()
        currentModes.remove(mode)
        val updated = _uiCustomizationState.value.copy(
            modeSpecificConfigs = currentModes
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Reset ${mode.name} layout to default")
    }

    fun saveCustomPreset(name: String, config: ModeLayoutConfig) {
        val preset = CustomUiPreset(
            id = "preset_${System.currentTimeMillis()}",
            name = name.ifBlank { "Preset ${_uiCustomizationState.value.customPresets.size + 1}" },
            templateType = UiTemplateType.CUSTOM,
            config = config
        )
        val currentPresets = _uiCustomizationState.value.customPresets.toMutableList()
        currentPresets.add(preset)
        val updated = _uiCustomizationState.value.copy(customPresets = currentPresets)
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Saved preset: ${preset.name}")
    }

    fun applyCustomLayoutConfig(config: ModeLayoutConfig) {
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = UiTemplateType.CUSTOM,
            globalConfig = config,
            modeSpecificConfigs = emptyMap()
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Custom UI applied to Camera")
    }

    fun loadCustomPreset(preset: CustomUiPreset) {
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = preset.templateType,
            globalConfig = preset.config,
            modeSpecificConfigs = emptyMap()
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Loaded preset: ${preset.name}")
    }

    fun deleteCustomPreset(presetId: String) {
        val currentPresets = _uiCustomizationState.value.customPresets.filterNot { it.id == presetId }
        val updated = _uiCustomizationState.value.copy(customPresets = currentPresets)
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Preset removed")
    }

    fun resetLayoutToTemplate(template: UiTemplateType) {
        val templateConfig = CameraUiTemplates.getTemplateConfig(template)
        val updated = _uiCustomizationState.value.copy(
            selectedTemplate = template,
            globalConfig = templateConfig,
            modeSpecificConfigs = emptyMap()
        )
        _uiCustomizationState.value = updated
        preferences.uiCustomizationState = updated
        showToast("Reset all layouts to ${template.title}")
    }

    // Extended Settings & Features State
    private val _videoCodec = MutableStateFlow(preferences.videoCodec)
    val videoCodec: StateFlow<String> = _videoCodec.asStateFlow()

    fun setVideoCodec(codec: String) {
        _videoCodec.value = codec
        preferences.videoCodec = codec
        showToast("Video Codec: $codec")
    }

    private val _jpegQuality = MutableStateFlow(preferences.jpegQuality)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    fun setJpegQuality(quality: Int) {
        _jpegQuality.value = quality
        preferences.jpegQuality = quality
        showToast("JPEG Quality: $quality%")
    }

    private val _volumeKeyAction = MutableStateFlow(preferences.volumeKeyAction)
    val volumeKeyAction: StateFlow<String> = _volumeKeyAction.asStateFlow()

    fun setVolumeKeyAction(action: String) {
        _volumeKeyAction.value = action
        preferences.volumeKeyAction = action
        showToast("Volume key set to: $action")
    }

    private val _doubleTapAction = MutableStateFlow(preferences.doubleTapAction)
    val doubleTapAction: StateFlow<String> = _doubleTapAction.asStateFlow()

    fun setDoubleTapAction(action: String) {
        _doubleTapAction.value = action
        preferences.doubleTapAction = action
        showToast("Double-tap set to: $action")
    }

    private val _showHorizonLevel = MutableStateFlow(preferences.showHorizonLevel)
    val showHorizonLevel: StateFlow<Boolean> = _showHorizonLevel.asStateFlow()

    fun setShowHorizonLevel(show: Boolean) {
        _showHorizonLevel.value = show
        preferences.showHorizonLevel = show
    }

    private val _antiBanding = MutableStateFlow(preferences.antiBanding)
    val antiBanding: StateFlow<String> = _antiBanding.asStateFlow()

    fun setAntiBanding(mode: String) {
        _antiBanding.value = mode
        preferences.antiBanding = mode
        showToast("Anti-banding: $mode")
    }

    private val _zoomSpeed = MutableStateFlow(preferences.zoomSpeed)
    val zoomSpeed: StateFlow<String> = _zoomSpeed.asStateFlow()

    fun setZoomSpeed(speed: String) {
        _zoomSpeed.value = speed
        preferences.zoomSpeed = speed
    }

    private val _audioSource = MutableStateFlow(preferences.audioSource)
    val audioSource: StateFlow<String> = _audioSource.asStateFlow()

    fun setAudioSource(source: String) {
        _audioSource.value = source
        preferences.audioSource = source
        showToast("Audio source: $source")
    }

    private val _previewQuality = MutableStateFlow(preferences.previewQuality)
    val previewQuality: StateFlow<String> = _previewQuality.asStateFlow()

    fun setPreviewQuality(quality: String) {
        _previewQuality.value = quality
        preferences.previewQuality = quality
        showToast("Preview Quality: $quality")
    }

    private val _shutterFeedback = MutableStateFlow(preferences.shutterFeedback)
    val shutterFeedback: StateFlow<String> = _shutterFeedback.asStateFlow()

    fun setShutterFeedback(feedback: String) {
        _shutterFeedback.value = feedback
        preferences.shutterFeedback = feedback
    }

    val antibandingMode: StateFlow<String> = _antiBanding.asStateFlow()
    fun setAntibandingMode(mode: String) = setAntiBanding(mode)

    private val _windNoiseReduction = MutableStateFlow(true)
    val windNoiseReduction: StateFlow<Boolean> = _windNoiseReduction.asStateFlow()
    fun setWindNoiseReduction(enabled: Boolean) {
        _windNoiseReduction.value = enabled
        showToast("Wind Noise Reduction: " + if (enabled) "On" else "Off")
    }

    val horizonLeveler: StateFlow<Boolean> = _showHorizonLevel.asStateFlow()
    fun setHorizonLeveler(show: Boolean) = setShowHorizonLevel(show)

    private val _viewfinderFps = MutableStateFlow(60)
    val viewfinderFps: StateFlow<Int> = _viewfinderFps.asStateFlow()
    fun setViewfinderFps(fps: Int) {
        _viewfinderFps.value = fps
        showToast("Viewfinder: $fps FPS")
    }

    private val _thermalProtection = MutableStateFlow(true)
    val thermalProtection: StateFlow<Boolean> = _thermalProtection.asStateFlow()
    fun setThermalProtection(enabled: Boolean) {
        _thermalProtection.value = enabled
        showToast("Thermal Protection: " + if (enabled) "Adaptive" else "Off")
    }

    private val _autoHdrEnabled = MutableStateFlow(preferences.autoHdrEnabled)
    val autoHdrEnabled: StateFlow<Boolean> = _autoHdrEnabled.asStateFlow()
    val isAutoHdrEnabled: StateFlow<Boolean> = _autoHdrEnabled.asStateFlow()

    fun setAutoHdrEnabled(enabled: Boolean) {
        _autoHdrEnabled.value = enabled
        preferences.autoHdrEnabled = enabled
        showToast(if (enabled) "Auto HDR Enabled" else "Auto HDR Disabled")
    }

    private val _autoFramingEnabled = MutableStateFlow(preferences.autoFramingEnabled)
    val autoFramingEnabled: StateFlow<Boolean> = _autoFramingEnabled.asStateFlow()
    val isAiAutoFramingEnabled: StateFlow<Boolean> = _autoFramingEnabled.asStateFlow()

    fun setAutoFramingEnabled(enabled: Boolean) {
        _autoFramingEnabled.value = enabled
        preferences.autoFramingEnabled = enabled
        showToast(if (enabled) "AI Auto-Framing On" else "AI Auto-Framing Off")
    }

    fun setAiAutoFramingEnabled(enabled: Boolean) = setAutoFramingEnabled(enabled)

    fun setPortraitConfig(config: PortraitConfig) {
        _portraitConfig.value = config
        preferences.portraitBlurStrength = config.blurStrength
        preferences.portraitAperture = config.simulatedAperture
    }

    fun setPhotoFilter(filter: PhotoFilter) {
        _selectedPhotoFilter.value = filter
        showToast("Filter: ${filter.displayName}")
    }

    fun setManualShutterSpeed(ns: Long?) {
        setManualShutterSpeedNs(ns)
    }

    fun setFlashMode(mode: FlashMode) {
        _flashMode.value = mode
        preferences.flashMode = mode
        engine.flashMode = mode
        engine.updatePreviewSettings()
    }

    fun setTimerMode(mode: TimerMode) {
        _timerMode.value = mode
        preferences.timerMode = mode
    }

    fun resetAllSettings() {
        resetAllSettingsToDefaults()
    }

    fun resetAllSettingsToDefaults() {
        preferences.resetAllSettingsToDefaults()
        selectUiTemplate(UiTemplateType.STOCK_PIXEL)
        setCameraMode(CameraMode.PHOTO)
        setGridType(GridType.NONE)
        setFlashMode(FlashMode.OFF)
        setTimerMode(TimerMode.OFF)
        _videoCodec.value = "HEVC"
        _jpegQuality.value = 100
        _showHorizonLevel.value = true
        _autoHdrEnabled.value = true
        _autoFramingEnabled.value = true
        showToast("All settings reset to defaults")
    }

    fun showToast(message: String) {
        _toastMessage.value = message
        toastDismissJob?.cancel()
        toastDismissJob = viewModelScope.launch {
            delay(2500)
            _toastMessage.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }
}
