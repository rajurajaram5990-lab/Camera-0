package com.example.camera.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.view.Surface
import com.example.camera.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private const val TAG = "Camera2Engine"

class Camera2Engine(private val context: Context) {

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    // Background threads
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Camera instances
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    // Surfaces & Readers
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var imageReaderJpeg: ImageReader? = null
    private var imageReaderRaw: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoRecordingFileDescriptor: ParcelFileDescriptor? = null
    private var currentRecordingTempFile: File? = null
    private var currentVideoUri: Uri? = null
    private var currentVideoFileName: String? = null
    private var currentVideoMimeType: String? = null

    // Initialization & Safety States
    private val _isCameraInitialized = MutableStateFlow(false)
    val isCameraInitialized: StateFlow<Boolean> = _isCameraInitialized.asStateFlow()

    private val _cameraInitError = MutableStateFlow<String?>(null)
    val cameraInitError: StateFlow<String?> = _cameraInitError.asStateFlow()

    fun getCharacteristics(cameraId: String): CameraCharacteristics? {
        val mgr = cameraManager ?: return null
        return try {
            mgr.getCameraCharacteristics(cameraId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get characteristics for camera $cameraId", t)
            null
        }
    }

    // State Flows
    private val _availableLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableLenses: StateFlow<List<LensInfo>> = _availableLenses.asStateFlow()

    private val _selectedLens = MutableStateFlow<LensInfo?>(null)
    val selectedLens: StateFlow<LensInfo?> = _selectedLens.asStateFlow()

    private val _capabilities = MutableStateFlow(HardwareCapabilities())
    val capabilities: StateFlow<HardwareCapabilities> = _capabilities.asStateFlow()

    private val _selectedPhotoResolution = MutableStateFlow<CameraResolution?>(null)
    val selectedPhotoResolution: StateFlow<CameraResolution?> = _selectedPhotoResolution.asStateFlow()

    private val _selectedVideoResolution = MutableStateFlow<CameraResolution?>(null)
    val selectedVideoResolution: StateFlow<CameraResolution?> = _selectedVideoResolution.asStateFlow()

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _isRecordingVideo = MutableStateFlow(false)
    val isRecordingVideo: StateFlow<Boolean> = _isRecordingVideo.asStateFlow()

    private val _videoDurationSeconds = MutableStateFlow(0)
    val videoDurationSeconds: StateFlow<Int> = _videoDurationSeconds.asStateFlow()

    private val _lastCapturedMedia = MutableStateFlow<CapturedMedia?>(null)
    val lastCapturedMedia: StateFlow<CapturedMedia?> = _lastCapturedMedia.asStateFlow()

    fun setLastCapturedMedia(media: CapturedMedia?) {
        _lastCapturedMedia.value = media
    }

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _previewAspectRatio = MutableStateFlow(4f / 3f)
    val previewAspectRatio: StateFlow<Float> = _previewAspectRatio.asStateFlow()

    private var videoTimerJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Capture Settings State
    var currentMode: CameraMode = CameraMode.PHOTO
    var flashMode: FlashMode = FlashMode.OFF
    var whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO
    var focusMode: FocusMode = FocusMode.CONTINUOUS
    var manualFocusDistance: Float = 0f // 0 = infinity, max = closest
    var manualIso: Int? = null // null = auto
    var manualExposureTimeNs: Long? = null // null = auto
    var exposureCompensationIndex: Int = 0
    var isAeLocked: Boolean = false
    var isAfLocked: Boolean = false
    var isRawCaptureEnabled: Boolean = false
    var isVideoStabilizationEnabled: Boolean = true
    var videoBitrateOption: VideoBitrateOption = VideoBitrateOption.AUTO
    var videoFps: Int = 30
    var colorProfile: ColorProfile = ColorProfile.STANDARD
    var isAudioEnabled: Boolean = true
    var currentZoom: Float = 1.0f
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoomState: StateFlow<Float> = _currentZoom.asStateFlow()
    var saveSelfieAsPreviewed: Boolean = true
    var viewfinderResolution: ViewfinderResolution = ViewfinderResolution.NORMAL
    var selectedPhotoFilter: PhotoFilter = PhotoFilter.ORIGINAL
    private val cinemaSoftwareRecorder by lazy { CinemaSoftwareRecordingEngine(context) }
    private var isSoftwareCinemaRecording: Boolean = false

    private val _previewBufferSize = MutableStateFlow<Size?>(null)
    val previewBufferSize: StateFlow<Size?> = _previewBufferSize.asStateFlow()

    private val _sensorOrientation = MutableStateFlow(90)
    val sensorOrientation: StateFlow<Int> = _sensorOrientation.asStateFlow()

    private var cameraOpenRetryCount = 0
    private val MAX_CAMERA_OPEN_RETRIES = 3

    private val _isAeLockedFlow = MutableStateFlow(false)
    val isAeLockedFlow: StateFlow<Boolean> = _isAeLockedFlow.asStateFlow()

    private val _isAfLockedFlow = MutableStateFlow(false)
    val isAfLockedFlow: StateFlow<Boolean> = _isAfLockedFlow.asStateFlow()

    val dollyZoomEngine = DollyZoomEngine(context)
    val nightFusionProcessor = NightFusionProcessor()
    val gyroStabilizationEngine = GyroStabilizationEngine(context)

    private val _hybridStabilizationConfig = MutableStateFlow(HybridStabilizationConfig())
    val hybridStabilizationConfig: StateFlow<HybridStabilizationConfig> = _hybridStabilizationConfig.asStateFlow()

    private val _nightProgress = MutableStateFlow(NightCaptureProgress())
    val nightProgress: StateFlow<NightCaptureProgress> = _nightProgress.asStateFlow()

    fun updateHybridStabilizationConfig(config: HybridStabilizationConfig) {
        _hybridStabilizationConfig.value = config
        val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA ||
                currentMode == CameraMode.DOLLY_ZOOM
        if (config.isUltraStabilizationEnabled && isVideoMode) {
            gyroStabilizationEngine.start()
        } else {
            gyroStabilizationEngine.stop()
            lastStabilizedCrop = null
        }
        updatePreviewSettings()
    }

    // Camera Session Concurrency & State Guard
    private val cameraLifecycleLock = Any()
    @Volatile
    private var isStartingCamera = false
    @Volatile
    private var isClosingCamera = false
    @Volatile
    private var isConfiguringSession = false
    @Volatile
    private var restartPending = false
    private var zoomDebounceJob: Job? = null

    val videoHdrEngine = VideoHdrEngine()
    private val _videoHdrState = MutableStateFlow(videoHdrEngine.currentState)
    val videoHdrState: StateFlow<VideoHdrState> = _videoHdrState.asStateFlow()

    val cinemaEngine = CinemaEngine(context)
    private val _cinemaConfig = MutableStateFlow(cinemaEngine.config)
    val cinemaConfig: StateFlow<CinemaConfig> = _cinemaConfig.asStateFlow()
    private val _cinemaCapabilities = MutableStateFlow(cinemaEngine.capabilities)
    val cinemaCapabilities: StateFlow<CinemaHardwareCapabilities> = _cinemaCapabilities.asStateFlow()

    val realEsrganEngine = com.example.camera.esrgan.RealEsrganEngine(context)
    val refocusEngine = RefocusEngine(context)
    val dbsrEngine = com.example.camera.dbsr.DbsrEngine(context)
    var photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12
    var isRefocusPhotoEnabled: Boolean = false
    var isAiZoomEnabled: Boolean = false
    var aiZoomQuality: com.example.camera.dbsr.AiZoomQuality = com.example.camera.dbsr.AiZoomQuality.AUTO
    private val _isAiZoomProcessing = MutableStateFlow(false)
    val isAiZoomProcessing: StateFlow<Boolean> = _isAiZoomProcessing.asStateFlow()
    private val _aiZoomProgress = MutableStateFlow(0f)
    val aiZoomProgress: StateFlow<Float> = _aiZoomProgress.asStateFlow()

    init {
        videoHdrEngine.onStateChangedListener = { state ->
            _videoHdrState.value = state
        }
        // ZERO hardware calls during construction/init!
        // Background threads, camera detection, and initialization are performed lazily
        // via safeInitializeCamera() only after CAMERA permission is confirmed.
    }

    /**
     * Centralized, safe camera initialization function.
     * Guaranteed to never throw an uncaught exception to the caller.
     * Must be called only after CAMERA permission is granted.
     */
    fun safeInitializeCamera(onResult: (success: Boolean, errorMessage: String?) -> Unit = { _, _ -> }) {
        if (_isCameraInitialized.value) {
            Log.d(TAG, "safeInitializeCamera: Already initialized")
            onResult(true, null)
            return
        }

        try {
            // 1. Verify context & permission
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "safeInitializeCamera: CAMERA permission not granted")
                _cameraInitError.value = "Camera permission not granted"
                onResult(false, _cameraInitError.value)
                return
            }

            // 2. Safely obtain CameraManager
            val mgr = cameraManager ?: run {
                Log.e(TAG, "safeInitializeCamera: Camera service unavailable on this device")
                _cameraInitError.value = "Camera service is unavailable on this device"
                onResult(false, _cameraInitError.value)
                return
            }

            // 3. Start background thread safely with UncaughtExceptionHandler
            startBackgroundThread()

            // 4. Enumerate camera IDs inside try/catch
            val officialIds = try {
                mgr.cameraIdList.toList()
            } catch (t: Throwable) {
                Log.e(TAG, "safeInitializeCamera: Failed to enumerate camera IDs", t)
                emptyList<String>()
            }

            if (officialIds.isEmpty()) {
                Log.w(TAG, "safeInitializeCamera: No camera IDs reported by device")
                _cameraInitError.value = "No camera hardware detected on this device"
                _isCameraInitialized.value = true
                onResult(false, _cameraInitError.value)
                return
            }

            // 5. Run physical camera/lens detection
            try {
                detectHardwareLenses()
            } catch (t: Throwable) {
                Log.e(TAG, "safeInitializeCamera: Error during lens detection", t)
            }

            // 6. Update storage stats
            try {
                updateStorageStats()
            } catch (t: Throwable) {
                Log.w(TAG, "safeInitializeCamera: Error updating storage stats", t)
            }

            _isCameraInitialized.value = true
            _cameraInitError.value = null

            // 7. If surface texture is already available, start camera
            if (previewSurfaceTexture != null) {
                startCamera()
            }

            onResult(true, null)
        } catch (t: Throwable) {
            Log.e(TAG, "safeInitializeCamera: Critical failure during camera initialization", t)
            val msg = "Camera initialization failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
            _cameraInitError.value = msg
            _isCameraInitialized.value = false
            onResult(false, msg)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("Camera2Background").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                    Log.e(TAG, "Uncaught exception on Camera2Background: ${thread.name}", throwable)
                }
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    /**
     * Detects all real physical and logical lenses available on the device,
     * including hidden auxiliary cameras, multi-camera physical streams, and integrated ultra-wide zoom ratios.
     */
    fun detectHardwareLenses(forceDeepScan: Boolean = false): Int {
        val mgr = cameraManager ?: return 0
        try {
            val officialIds = try {
                mgr.cameraIdList.toList()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to get cameraIdList", t)
                emptyList<String>()
            }
            val candidateIds = linkedSetOf<String>()
            candidateIds.addAll(officialIds)

            // Discover vendor-hidden auxiliary camera IDs (common on Samsung, Xiaomi, OnePlus, Vivo)
            for (testId in 0..12) {
                val sId = testId.toString()
                if (!candidateIds.contains(sId)) {
                    try {
                        val chars = mgr.getCameraCharacteristics(sId)
                        if (chars != null) {
                            candidateIds.add(sId)
                        }
                    } catch (ignored: Throwable) {}
                }
            }

            val lenses = mutableListOf<LensInfo>()
            val processedPhysicalIds = mutableSetOf<String>()

            val primaryBackId = candidateIds.firstOrNull { id ->
                try {
                    getCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } catch (t: Throwable) { false }
            } ?: candidateIds.firstOrNull() ?: "0"

            val primaryFrontId = candidateIds.firstOrNull { id ->
                try {
                    getCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } catch (t: Throwable) { false }
            } ?: "1"

            for (id in candidateIds) {
                try {
                    val chars = getCharacteristics(id) ?: continue
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.0f)
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                    val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val maxAperture = apertures.firstOrNull() ?: 1.8f
                    val primaryFocalMm = focalLengths.firstOrNull() ?: 4.0f

                    val cropFactor = if (sensorSize != null && sensorSize.width > 0) {
                        36f / sensorSize.width
                    } else {
                        7f
                    }

                    // For each focal length supported by this camera ID
                    for (focalMm in focalLengths) {
                        val eq35mm = focalMm * cropFactor
                        val fovDegrees = if (sensorSize != null && sensorSize.width > 0 && focalMm > 0) {
                            (2.0 * kotlin.math.atan(sensorSize.width.toDouble() / (2.0 * focalMm.toDouble())) * (180.0 / Math.PI)).toFloat()
                        } else {
                            0f
                        }

                        val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
                        val isUltraWide = isBack && ((eq35mm in 1.0f..23.4f) || focalMm <= 2.8f || fovDegrees >= 88.0f)
                        val isTele3x = isBack && (eq35mm >= 70f || focalMm >= 9.0f)
                        val isTele2x = isBack && !isTele3x && (eq35mm in 45f..70f || focalMm in 5.9f..9.0f)
                        val isMacro = isBack && minFocus > 10f && focalMm < 3.2f
                        val isMain = isBack && !isUltraWide && !isTele3x && !isTele2x && !isMacro

                        val lensType = when {
                            facing == CameraCharacteristics.LENS_FACING_FRONT -> LensType.FRONT
                            isUltraWide -> LensType.ULTRAWIDE
                            isTele3x -> LensType.TELEPHOTO_3X
                            isTele2x -> LensType.TELEPHOTO
                            isMacro -> LensType.MACRO
                            else -> LensType.WIDE
                        }

                        val isOfficial = officialIds.contains(id)
                        val idDesc = when {
                            !isOfficial -> "Hidden Aux ID $id"
                            else -> "Camera ID $id"
                        }

                        val displayName = when (lensType) {
                            LensType.FRONT -> "Front Selfie (f/${maxAperture})"
                            LensType.ULTRAWIDE -> "0.5x Ultra Wide (${focalMm}mm f/${maxAperture})"
                            LensType.WIDE -> "1x Main (${focalMm}mm f/${maxAperture})"
                            LensType.TELEPHOTO -> "2x Telephoto (${focalMm}mm f/${maxAperture})"
                            LensType.TELEPHOTO_3X -> "3x Telephoto (${focalMm}mm f/${maxAperture})"
                            LensType.MACRO -> "Macro (${focalMm}mm)"
                        }

                        lenses.add(
                            LensInfo(
                                cameraId = id,
                                facing = facing,
                                lensType = lensType,
                                displayName = displayName,
                                focalLengthMm = focalMm,
                                maxAperture = maxAperture,
                                isPhysical = true,
                                isHiddenAux = !isOfficial,
                                isZoomPreset = false,
                                baseZoomRatio = when (lensType) {
                                    LensType.ULTRAWIDE -> 0.5f
                                    LensType.WIDE -> 1.0f
                                    LensType.TELEPHOTO -> 2.0f
                                    LensType.TELEPHOTO_3X -> 3.0f
                                    LensType.MACRO -> 1.0f
                                    LensType.FRONT -> 1.0f
                                },
                                fovDegrees = fovDegrees,
                                equivalent35mmFocalMm = eq35mm,
                                idTypeDescription = idDesc
                            )
                        )
                    }

                    // Android 9+ Physical camera inspection inside logical multi-camera
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val physicalCameraIds = chars.physicalCameraIds
                        for (physId in physicalCameraIds) {
                            if (!processedPhysicalIds.contains(physId)) {
                                processedPhysicalIds.add(physId)
                                try {
                                    val physChars = getCharacteristics(physId) ?: continue
                                    val pFacing = physChars.get(CameraCharacteristics.LENS_FACING) ?: facing
                                    val pFocals = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4f)
                                    val pApertures = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                                    val pSensor = physChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                                    val pMinFocus = physChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                                    val pFocal = pFocals.firstOrNull() ?: 4f
                                    val pAperture = pApertures.firstOrNull() ?: 1.8f
                                    val pCrop = if (pSensor != null && pSensor.width > 0) 36f / pSensor.width else 7f
                                    val pEq35 = pFocal * pCrop
                                    val pFov = if (pSensor != null && pSensor.width > 0 && pFocal > 0) {
                                        (2.0 * kotlin.math.atan(pSensor.width.toDouble() / (2.0 * pFocal.toDouble())) * (180.0 / Math.PI)).toFloat()
                                    } else 0f

                                    val isPUltraWide = (pEq35 in 1.0f..23.5f) || pFocal <= 2.6f || pFov >= 75.0f
                                    val isPTele3x = pEq35 >= 70f || pFocal >= 9.0f
                                    val isPTele2x = pEq35 in 45f..70f || pFocal in 5.8f..9.0f
                                    val isPMacro = pMinFocus > 10f && pFocal < 3.2f

                                    val pType = when {
                                        pFacing == CameraCharacteristics.LENS_FACING_FRONT -> LensType.FRONT
                                        isPUltraWide -> LensType.ULTRAWIDE
                                        isPTele3x -> LensType.TELEPHOTO_3X
                                        isPTele2x -> LensType.TELEPHOTO
                                        isPMacro -> LensType.MACRO
                                        else -> LensType.WIDE
                                    }

                                    val openableId = if (candidateIds.contains(physId)) physId else id
                                    val isHidden = !officialIds.contains(openableId)
                                    lenses.add(
                                        LensInfo(
                                            cameraId = openableId,
                                            facing = pFacing,
                                            lensType = pType,
                                            displayName = "Physical $physId (${pType.shortLabel} · ${pFocal}mm)",
                                            focalLengthMm = pFocal,
                                            maxAperture = pAperture,
                                            isPhysical = true,
                                            isHiddenAux = isHidden,
                                            isZoomPreset = false,
                                            baseZoomRatio = when (pType) {
                                                LensType.ULTRAWIDE -> 0.5f
                                                LensType.WIDE -> 1.0f
                                                LensType.TELEPHOTO -> 2.0f
                                                LensType.TELEPHOTO_3X -> 3.0f
                                                LensType.MACRO -> 1.0f
                                                LensType.FRONT -> 1.0f
                                            },
                                            physicalCameraId = physId,
                                            fovDegrees = pFov,
                                            equivalent35mmFocalMm = pEq35,
                                            idTypeDescription = "Physical Multi-Cam ID $physId"
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error inspecting physical camera $physId", e)
                                }
                            }
                        }
                    }

                    // Android 11+ Zoom Ratio Range (< 1.0f indicates integrated hardware Ultra Wide)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        if (zoomRange != null) {
                            val minZoom = zoomRange.lower
                            val maxZoom = zoomRange.upper

                            // If camera supports < 1.0f (e.g. 0.5x, 0.6x), expose Ultra Wide preset
                            if (minZoom <= 0.9f && lenses.none { it.cameraId == id && it.lensType == LensType.ULTRAWIDE && it.isZoomPreset }) {
                                val ultraLabel = if (minZoom <= 0.55f) "0.5x" else "%.1fx".format(minZoom)
                                lenses.add(
                                    LensInfo(
                                        cameraId = id,
                                        facing = facing,
                                        lensType = LensType.ULTRAWIDE,
                                        displayName = "$ultraLabel Ultra Wide (Optical Stream)",
                                        focalLengthMm = primaryFocalMm,
                                        maxAperture = maxAperture,
                                        isPhysical = false,
                                        isHiddenAux = false,
                                        isZoomPreset = true,
                                        baseZoomRatio = minZoom,
                                        fovDegrees = 110f,
                                        equivalent35mmFocalMm = 14f,
                                        idTypeDescription = "Optical Ultra-Wide (Zoom $ultraLabel)"
                                    )
                                )
                            }

                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting camera $id", e)
                }
            }

            // Ensure physical back and front cameras have at least a baseline entry if hardware exists:
            val hasBack = lenses.any { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            val hasFront = lenses.any { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

            // Baseline Main Wide (1x) if no back camera detected yet
            if (!hasBack) {
                lenses.add(
                    LensInfo(
                        cameraId = primaryBackId,
                        facing = CameraCharacteristics.LENS_FACING_BACK,
                        lensType = LensType.WIDE,
                        displayName = "1x Main Camera",
                        focalLengthMm = 4.2f,
                        maxAperture = 1.8f,
                        isPhysical = true,
                        isHiddenAux = false,
                        isZoomPreset = false,
                        baseZoomRatio = 1.0f,
                        fovDegrees = 78f,
                        equivalent35mmFocalMm = 24f,
                        idTypeDescription = "Main Camera (1x)"
                    )
                )
            }

            // Baseline Front Selfie Camera if no front camera detected yet
            if (!hasFront) {
                lenses.add(
                    LensInfo(
                        cameraId = primaryFrontId,
                        facing = CameraCharacteristics.LENS_FACING_FRONT,
                        lensType = LensType.FRONT,
                        displayName = "Front Selfie Camera",
                        focalLengthMm = 3.5f,
                        maxAperture = 2.0f,
                        isPhysical = true,
                        isHiddenAux = false,
                        isZoomPreset = false,
                        baseZoomRatio = 1.0f,
                        fovDegrees = 85f,
                        equivalent35mmFocalMm = 22f,
                        idTypeDescription = "Front Camera (1x)"
                    )
                )
            }

            // Clean, de-duplicate and sort lenses intuitively:
            // 1. Back Ultra-Wide (0.5x)
            // 2. Back Main Wide (1x)
            // 3. Back Telephoto (2x / 3x)
            // 4. Back Macro
            // 5. Additional Physical/Aux lenses
            // 6. Front Selfie (1x)
            val sortedLenses = lenses.distinctBy {
                "${it.cameraId}_${it.lensType.name}_${it.isZoomPreset}_${it.baseZoomRatio}_${it.isPhysical}"
            }.sortedWith(
                compareBy<LensInfo> { it.facing }
                    .thenBy {
                        when (it.lensType) {
                            LensType.ULTRAWIDE -> 0
                            LensType.WIDE -> 1
                            LensType.TELEPHOTO -> 2
                            LensType.TELEPHOTO_3X -> 3
                            LensType.MACRO -> 4
                            LensType.FRONT -> 5
                        }
                    }
                    .thenBy { it.baseZoomRatio }
                    .thenBy { if (it.isPhysical) 0 else 1 }
            )

            _availableLenses.value = sortedLenses

            // Maintain current selection or pick 1x Main Wide
            val currentSelected = _selectedLens.value
            val validSelection = sortedLenses.firstOrNull { it.id == currentSelected?.id }
                ?: sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.lensType == LensType.WIDE && !it.isZoomPreset }
                ?: sortedLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                ?: sortedLenses.firstOrNull()

            _selectedLens.value = validSelection
            if (validSelection != null) {
                inspectCapabilities(validSelection.cameraId)
            }

            Log.i(TAG, "Total discovered lenses after deep scan: ${sortedLenses.size}")
            return sortedLenses.size
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to detect hardware lenses", t)
            return 0
        }
    }

    /**
     * Inspects actual hardware capabilities of the given camera ID.
     */
    fun inspectCapabilities(cameraId: String) {
        try {
            val chars = getCharacteristics(cameraId) ?: return
            videoHdrEngine.onCameraConfigured(chars)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
            val exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
            val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-4, 4)
            val aeCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f

            // OIS / EIS
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            val hasOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
            val hasEis = eisModes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)

            // AWB modes
            val availableAwb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
            val awbModes = WhiteBalanceMode.entries.filter { availableAwb.contains(it.camera2Mode) }

            // AF modes
            val availableAf = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val afModes = FocusMode.entries.filter { availableAf.contains(it.camera2Mode) }

            // Photo JPEG Resolutions (incorporates native high-resolution & sensor remosaic modes)
            val standardSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val highResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    map?.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()

            val maxResSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    val m1 = maxResMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    val m2 = maxResMap?.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    m1 + m2
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()

            val allJpegSizes = (standardSizes + highResSizes + maxResSizes).distinctBy { "${it.width}x${it.height}" }
            val photoResolutions = allJpegSizes
                .sortedByDescending { it.width * it.height }
                .map { CameraResolution(it.width, it.height, ImageFormat.JPEG, isRaw = false) }

            // RAW Resolutions
            val standardRawSizes = if (hasRaw) {
                map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            } else emptyArray()
            val highResRawSizes = if (hasRaw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    map?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()
            val maxResRawSizes = if (hasRaw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    val m1 = maxResMap?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                    val m2 = maxResMap?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
                    m1 + m2
                } catch (e: Exception) {
                    emptyArray()
                }
            } else emptyArray()

            val allRawSizes = (standardRawSizes + highResRawSizes + maxResRawSizes).distinctBy { "${it.width}x${it.height}" }
            val rawResolutions = allRawSizes
                .sortedByDescending { it.width * it.height }
                .map { CameraResolution(it.width, it.height, ImageFormat.RAW_SENSOR, isRaw = true) }

            // Video Resolutions
            val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()

            val standardVideoQualities = listOf(
                CameraResolution(3840, 2160), // 4K UHD (Supported across 0.5x, 1x and Selfie)
                CameraResolution(1920, 1080), // 1080p FHD
                CameraResolution(1280, 720),  // 720p HD
                CameraResolution(720, 480)    // 480p SD
            )
            val filteredVideoResolutions = standardVideoQualities.filter { standard ->
                videoSizes.isEmpty() || videoSizes.any { it.width == standard.width && it.height == standard.height } || standard.width <= 3840
            }.ifEmpty {
                listOf(
                    CameraResolution(3840, 2160),
                    CameraResolution(1920, 1080),
                    CameraResolution(1280, 720)
                )
            }

            // FPS ranges
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 30
            val supportedFps = if (maxFps >= 60) listOf(30, 60) else listOf(30)

            // Tonemap curve
            val tonemapModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            val hasTonemapCurve = tonemapModes.contains(CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE) ||
                    tonemapModes.contains(CameraCharacteristics.TONEMAP_MODE_FAST)

            val hardwareCaps = HardwareCapabilities(
                supportsManualSensor = hasManualSensor,
                supportsRaw = hasRaw && rawResolutions.isNotEmpty(),
                supportsOis = hasOis,
                supportsEis = hasEis,
                supportsFlash = flashAvailable,
                minIso = isoRange.lower,
                maxIso = isoRange.upper,
                minExposureTimeNs = exposureTimeRange.lower,
                maxExposureTimeNs = exposureTimeRange.upper,
                minExposureCompensation = aeCompRange.lower,
                maxExposureCompensation = aeCompRange.upper,
                exposureCompensationStep = aeCompStep,
                minFocusDistance = minFocus,
                supportedAwbModes = awbModes.ifEmpty { listOf(WhiteBalanceMode.AUTO) },
                supportedAfModes = afModes.ifEmpty { listOf(FocusMode.CONTINUOUS) },
                supportedPhotoResolutions = photoResolutions,
                supportedRawResolutions = rawResolutions,
                supportedVideoResolutions = filteredVideoResolutions,
                supportedFpsRanges = supportedFps,
                supportsTonemapCurve = hasTonemapCurve,
                maxZoom = maxZoom
            )

            _capabilities.value = hardwareCaps

            cinemaEngine.onCameraConfigured(chars, filteredVideoResolutions)
            _cinemaCapabilities.value = cinemaEngine.capabilities

            // Default resolutions
            if (_selectedPhotoResolution.value == null || !photoResolutions.contains(_selectedPhotoResolution.value)) {
                _selectedPhotoResolution.value = photoResolutions.firstOrNull()
            }
            if (_selectedVideoResolution.value == null || !filteredVideoResolutions.contains(_selectedVideoResolution.value)) {
                _selectedVideoResolution.value = filteredVideoResolutions.firstOrNull { it.height == 1080 }
                    ?: filteredVideoResolutions.firstOrNull()
            }

            updatePreviewAspectRatio()
        } catch (t: Throwable) {
            Log.e(TAG, "Error inspecting capabilities for camera $cameraId", t)
        }
    }

    private fun updatePreviewAspectRatio() {
        if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
            val res = if (currentMode == CameraMode.CINEMA && cinemaConfig.value.selectedResolution != null) {
                cinemaConfig.value.selectedResolution
            } else {
                _selectedVideoResolution.value
            }
            if (res != null && res.height > 0) {
                val w = max(res.width, res.height).toFloat()
                val h = min(res.width, res.height).toFloat()
                _previewAspectRatio.value = w / h
            } else {
                _previewAspectRatio.value = 16f / 9f
            }
            return
        }

        val resolution = _selectedPhotoResolution.value
        if (resolution != null && resolution.height > 0) {
            // Standard sensor orientation width > height in landscape, in portrait aspect ratio is width / height
            val w = max(resolution.width, resolution.height).toFloat()
            val h = min(resolution.width, resolution.height).toFloat()
            _previewAspectRatio.value = w / h // e.g. 4/3 = 1.333, 16/9 = 1.777, 20/9 = 2.222
        } else {
            _previewAspectRatio.value = 4f / 3f
        }
    }

    /**
     * Switch active lens
     */
    fun selectLens(lens: LensInfo) {
        val previousLens = _selectedLens.value
        _selectedLens.value = lens
        currentZoom = lens.baseZoomRatio
        _currentZoom.value = lens.baseZoomRatio

        // If recording video, prioritize continuity to ensure zero distortion and no video stop:
        // Adjust optical zoom ratio and crop dynamically on the active recording stream
        if (_isRecordingVideo.value) {
            updatePreviewSettings()
            return
        }

        // If same camera ID and same facing, update optical zoom/crop dynamically without restarting hardware
        if (previousLens?.cameraId == lens.cameraId &&
            previousLens?.facing == lens.facing &&
            cameraDevice != null) {
            updatePreviewSettings()
            return
        }

        inspectCapabilities(lens.cameraId)
        restartCamera()
    }

    /**
     * Switch photo resolution
     */
    fun selectPhotoResolution(resolution: CameraResolution) {
        _selectedPhotoResolution.value = resolution
        updatePreviewAspectRatio()
        if (cameraDevice != null) {
            reconfigureSession()
        } else {
            restartCamera()
        }
    }

    /**
     * Switch video resolution
     */
    fun selectVideoResolution(resolution: CameraResolution) {
        _selectedVideoResolution.value = resolution
        updatePreviewAspectRatio()
        if (cameraDevice != null) {
            reconfigureSession()
        } else {
            restartCamera()
        }
    }

    /**
     * Restore saved video resolution without triggering camera restart before viewfinder is attached
     */
    fun restoreInitialVideoResolution(resolution: CameraResolution) {
        _selectedVideoResolution.value = resolution
        updatePreviewAspectRatio()
    }

    /**
     * Switch between Photo, Portrait, Video & Cinema modes smoothly without closing hardware device
     */
    fun setMode(mode: CameraMode) {
        if (currentMode == mode) return
        val wasPhotoOrPortrait = (currentMode == CameraMode.PHOTO || currentMode == CameraMode.PORTRAIT)
        val isPhotoOrPortrait = (mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT)
        val wasMore = (currentMode == CameraMode.MORE)
        if (_isRecordingVideo.value) {
            stopVideoRecording()
        }
        currentMode = mode
        updatePreviewAspectRatio()

        val isVideoMode = (mode == CameraMode.VIDEO || mode == CameraMode.CINEMA ||
                mode == CameraMode.DOLLY_ZOOM)
        if (!isVideoMode || !_hybridStabilizationConfig.value.isUltraStabilizationEnabled) {
            gyroStabilizationEngine.stop()
            lastStabilizedCrop = null
        } else if (isVideoMode && _hybridStabilizationConfig.value.isUltraStabilizationEnabled) {
            gyroStabilizationEngine.start()
        }

        if (mode == CameraMode.DOLLY_ZOOM) {
            dollyZoomEngine.start()
            if (!dollyZoomEngine.dollyState.value.isCalibrated) {
                backgroundHandler?.postDelayed({
                    calibrateDollyZoom()
                }, 350)
            }
        } else {
            dollyZoomEngine.stop()
        }

        val needsReconfigure = (wasPhotoOrPortrait != isPhotoOrPortrait) ||
                (wasMore && isPhotoOrPortrait) ||
                (captureSession == null) ||
                (!_isCameraReady.value)

        if (needsReconfigure) {
            if (cameraDevice != null) {
                reconfigureSession()
            } else {
                restartCamera()
            }
        } else {
            updatePreviewSettings()
        }
    }

    /**
     * Reconfigures CameraCaptureSession on the currently active CameraDevice.
     * Prevents hardware sensor re-opening, elimination of black screens and HAL contention.
     */
    fun reconfigureSession() {
        if (!_isCameraInitialized.value || previewSurfaceTexture == null || cameraDevice == null) {
            restartCamera()
            return
        }
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (isConfiguringSession || isStartingCamera) {
                    Log.d(TAG, "reconfigureSession already in progress, skipping")
                    return@synchronized
                }
                isConfiguringSession = true
                try {
                    val camera = cameraDevice ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val lens = _selectedLens.value ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val texture = previewSurfaceTexture ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val chars = getCharacteristics(lens.cameraId) ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
                        isConfiguringSession = false
                        return@synchronized
                    }
                    val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

                    val targetRatio = _previewAspectRatio.value
                    val maxDim = viewfinderResolution.maxDimension
                    val matchingRatioSizes = previewSizes.filter {
                        val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                        kotlin.math.abs(r - targetRatio) < 0.08f
                    }

                    val optimalPreviewSize = matchingRatioSizes
                        .filter { max(it.width, it.height) <= maxDim }
                        .maxByOrNull { it.width * it.height }
                        ?: matchingRatioSizes.minByOrNull { max(it.width, it.height) }
                        ?: previewSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                        ?: previewSizes.firstOrNull()
                        ?: Size(1920, 1080)

                    _previewBufferSize.value = optimalPreviewSize
                    texture.setDefaultBufferSize(optimalPreviewSize.width, optimalPreviewSize.height)

                    // Safely close previous session before reconfiguring
                    try {
                        captureSession?.stopRepeating()
                        captureSession?.abortCaptures()
                    } catch (ignored: Throwable) {}
                    try {
                        captureSession?.close()
                    } catch (ignored: Throwable) {}
                    captureSession = null
                    _isCameraReady.value = false

                    // Reuse existing valid surface if available to avoid BufferQueue disconnects
                    val curSurf = previewSurface
                    if (curSurf == null || !curSurf.isValid) {
                        try {
                            curSurf?.release()
                        } catch (ignored: Exception) {}
                        previewSurface = Surface(texture)
                    }

                    setupImageReaders(lens.cameraId)
                    createCameraCaptureSession()
                } catch (e: Exception) {
                    isConfiguringSession = false
                    Log.e(TAG, "reconfigureSession failed, falling back to restartCamera", e)
                    restartCamera()
                }
            }
        } ?: run {
            restartCamera()
        }
    }

    /**
     * Update Cinema Mode configuration and immediately apply to hardware ISP
     */
    fun setCinemaConfig(newConfig: CinemaConfig) {
        cinemaEngine.config = newConfig
        _cinemaConfig.value = newConfig
        if (currentMode == CameraMode.CINEMA) {
            updatePreviewAspectRatio()
            updatePreviewSettings()
        }
    }

    /**
     * Attach viewfinder surface texture from Compose AndroidView
     */
    fun setPreviewSurfaceTexture(texture: SurfaceTexture?) {
        val prevTexture = previewSurfaceTexture
        previewSurfaceTexture = texture
        if (texture != null) {
            if (prevTexture != texture || cameraDevice == null) {
                if (_isCameraInitialized.value) {
                    startCamera()
                }
            } else if (captureSession == null && !isConfiguringSession && !isStartingCamera) {
                reconfigureSession()
            }
        } else {
            closeCamera()
        }
    }

    /**
     * Start/Open Camera2 device
     */
    @SuppressLint("MissingPermission")
    fun startCamera() {
        if (!_isCameraInitialized.value) {
            Log.d(TAG, "startCamera deferred: camera not initialized yet")
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera aborted: CAMERA permission not granted")
            return
        }

        val lens = _selectedLens.value ?: return
        val texture = previewSurfaceTexture ?: return
        val mgr = cameraManager ?: return

        startBackgroundThread()

        synchronized(cameraLifecycleLock) {
            if (isStartingCamera) {
                restartPending = true
                return
            }
            if (cameraDevice != null) {
                reconfigureSession()
                return
            }
            isStartingCamera = true
        }

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

            // Pick optimal preview size matching selected aspect ratio and viewfinderResolution level
            val targetRatio = _previewAspectRatio.value
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

            val maxDim = viewfinderResolution.maxDimension
            val matchingRatioSizes = previewSizes.filter {
                val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                kotlin.math.abs(r - targetRatio) < 0.08f
            }

            val optimalPreviewSize = matchingRatioSizes
                .filter { max(it.width, it.height) <= maxDim }
                .maxByOrNull { it.width * it.height }
                ?: matchingRatioSizes.minByOrNull { max(it.width, it.height) }
                ?: previewSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                ?: previewSizes.firstOrNull()
                ?: Size(1920, 1080)

            val sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            _sensorOrientation.value = sensorOrient
            _previewBufferSize.value = optimalPreviewSize

            texture.setDefaultBufferSize(optimalPreviewSize.width, optimalPreviewSize.height)
            try {
                previewSurface?.release()
            } catch (ignored: Throwable) {}
            previewSurface = Surface(texture)

            // Setup ImageReader for Photo mode
            setupImageReaders(lens.cameraId)

            mgr.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenRetryCount = 0
                    cameraDevice = camera
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                            return
                        }
                    }
                    createCameraCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isCameraReady.value = false
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                        }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error (attempt $cameraOpenRetryCount)")
                    camera.close()
                    cameraDevice = null
                    _isCameraReady.value = false
                    synchronized(cameraLifecycleLock) {
                        isStartingCamera = false
                        if (restartPending) {
                            restartPending = false
                            backgroundHandler?.post { restartCamera() }
                            return
                        }
                    }
                    // Auto-recover from transient HAL contention or device busy error with bounded retries
                    if (cameraOpenRetryCount < MAX_CAMERA_OPEN_RETRIES &&
                        (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                         error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                         error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE)) {
                        cameraOpenRetryCount++
                        backgroundHandler?.postDelayed({
                            restartCamera()
                        }, 300L * cameraOpenRetryCount)
                    }
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start camera", t)
            synchronized(cameraLifecycleLock) {
                isStartingCamera = false
            }
            _isCameraReady.value = false
        }
    }

    private fun setupImageReaders(cameraId: String) {
        try {
            imageReaderJpeg?.close()
        } catch (ignored: Throwable) {}
        imageReaderJpeg = null

        try {
            imageReaderRaw?.close()
        } catch (ignored: Throwable) {}
        imageReaderRaw = null

        val caps = _capabilities.value
        val isSuperResMode = photoMegapixelMode.isSuperResolution
        val photoRes = if (isSuperResMode) {
            caps.supportedPhotoResolutions.maxByOrNull { it.width * it.height }
                ?: _selectedPhotoResolution.value
                ?: CameraResolution(4000, 3000)
        } else {
            _selectedPhotoResolution.value ?: CameraResolution(4000, 3000)
        }

        try {
            imageReaderJpeg = ImageReader.newInstance(
                photoRes.width,
                photoRes.height,
                ImageFormat.JPEG,
                4
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create ImageReader with ${photoRes.width}x${photoRes.height}, falling back to 1080p", t)
            try {
                imageReaderJpeg = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 4)
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed fallback ImageReader", t2)
            }
        }

        if (caps.supportsRaw && (isRawCaptureEnabled || isAiZoomEnabled) && caps.supportedRawResolutions.isNotEmpty()) {
            val rawRes = caps.supportedRawResolutions.first()
            try {
                imageReaderRaw = ImageReader.newInstance(
                    rawRes.width,
                    rawRes.height,
                    ImageFormat.RAW_SENSOR,
                    6
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create RAW ImageReader", t)
            }
        }
    }

    private fun createCameraCaptureSession() {
        val camera = cameraDevice ?: return
        val previewSurf = previewSurface ?: return

        val activeLens = _selectedLens.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            activeLens?.physicalCameraId != null &&
            activeLens.physicalCameraId != activeLens.cameraId) {
            try {
                val outputConfigs = mutableListOf<android.hardware.camera2.params.OutputConfiguration>()
                val previewConfig = android.hardware.camera2.params.OutputConfiguration(previewSurf)
                previewConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                outputConfigs.add(previewConfig)

                imageReaderJpeg?.surface?.let {
                    val jpegConfig = android.hardware.camera2.params.OutputConfiguration(it)
                    jpegConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                    outputConfigs.add(jpegConfig)
                }
                imageReaderRaw?.surface?.let {
                    val rawConfig = android.hardware.camera2.params.OutputConfiguration(it)
                    rawConfig.setPhysicalCameraId(activeLens.physicalCameraId)
                    outputConfigs.add(rawConfig)
                }

                val template = CameraDevice.TEMPLATE_PREVIEW
                previewRequestBuilder = camera.createCaptureRequest(template).apply {
                    addTarget(previewSurf)
                    applyCommonSettings(this)
                }

                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            isConfiguringSession = false
                            if (cameraDevice == null) return
                            captureSession = session
                            try {
                                previewRequestBuilder?.let {
                                    session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                }
                                _isCameraReady.value = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating preview request", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            isConfiguringSession = false
                            Log.e(TAG, "Camera capture session configuration failed, scheduling recovery")
                            _isCameraReady.value = false
                            backgroundHandler?.postDelayed({
                                restartCamera()
                            }, 250)
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Falling back to standard session creation: ${e.message}")
            }
        }

        try {
            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurf)

            imageReaderJpeg?.surface?.let { surfaces.add(it) }
            imageReaderRaw?.surface?.let { surfaces.add(it) }

            val template = CameraDevice.TEMPLATE_PREVIEW

            previewRequestBuilder = camera.createCaptureRequest(template).apply {
                addTarget(previewSurf)
                applyCommonSettings(this)
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        isConfiguringSession = false
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            previewRequestBuilder?.let {
                                session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                            }
                            _isCameraReady.value = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isConfiguringSession = false
                        Log.e(TAG, "Camera capture session configuration failed, scheduling recovery")
                        _isCameraReady.value = false
                        backgroundHandler?.postDelayed({
                            restartCamera()
                        }, 250)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            isConfiguringSession = false
            Log.e(TAG, "Failed to create camera capture session", e)
        }
    }

    private var lastCaptureResult: TotalCaptureResult? = null
    private var lastHdrUpdateRequestTime = 0L
    private var lastDollyApplyTime = 0L
    private var lastAppliedDollyZoom = 1.0f

    private var lastStabilizedCropTime = 0L
    private var lastStabilizedCrop: Rect? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            lastCaptureResult = result

            if (currentMode == CameraMode.DOLLY_ZOOM) {
                val lens = _selectedLens.value
                if (lens != null) {
                    try {
                        val chars = getCharacteristics(lens.cameraId) ?: return
                        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        } else null
                        val minZoom = zoomRange?.lower ?: 1.0f
                        val maxZoom = zoomRange?.upper ?: (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f)
                        val newZoom = dollyZoomEngine.processFrame(
                            result = result,
                            sensorRect = sensorRect,
                            minAvailableZoom = minZoom,
                            maxAvailableZoom = maxZoom
                        )
                        if (newZoom != null) {
                            applyContinuousDollyZoom(newZoom)
                        }
                    } catch (ignored: Exception) {}
                }
            } else if (_hybridStabilizationConfig.value.isUltraStabilizationEnabled &&
                (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA)) {
                val lens = _selectedLens.value
                if (lens != null) {
                    try {
                        val chars = getCharacteristics(lens.cameraId)
                        val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        val focalLengths = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focalLength = focalLengths?.firstOrNull() ?: 4.38f
                        val sensorSize = chars?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.4f, 4.8f)

                        if (activeArray != null) {
                            val crop = gyroStabilizationEngine.computeStabilizedCrop(
                                result = result,
                                activeArray = activeArray,
                                baseZoom = currentZoom,
                                focalLengthMm = focalLength,
                                sensorPhysicalSizeMm = sensorSize
                            )
                            if (crop != null) {
                                applyStabilizedCrop(crop)
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    private fun applyStabilizedCrop(crop: Rect) {
        val now = System.currentTimeMillis()
        if (now - lastStabilizedCropTime < 33) return // 30fps throttle
        val last = lastStabilizedCrop
        if (last != null &&
            kotlin.math.abs(crop.left - last.left) < 2 &&
            kotlin.math.abs(crop.top - last.top) < 2
        ) return

        lastStabilizedCropTime = now
        lastStabilizedCrop = crop

        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying stabilized crop", e)
        }
    }

    private fun applyContinuousDollyZoom(zoom: Float) {
        val now = System.currentTimeMillis()
        if (now - lastDollyApplyTime < 16) return // 60fps responsive tracking
        if (kotlin.math.abs(zoom - lastAppliedDollyZoom) < 0.002f) return
        lastDollyApplyTime = now
        lastAppliedDollyZoom = zoom

        currentZoom = zoom
        _currentZoom.value = zoom

        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            } else {
                val chars = getCharacteristics(_selectedLens.value?.cameraId ?: "0")
                val activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    val cropW = (activeArray.width() / zoom).toInt()
                    val cropH = (activeArray.height() / zoom).toInt()
                    val cropX = (activeArray.width() - cropW) / 2
                    val cropY = (activeArray.height() - cropH) / 2
                    builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }
            }
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying continuous dolly zoom", e)
        }
    }

    /**
     * Apply AE, AF, AWB, Flash, ISO, Shutter, Zoom, Stabilization to CaptureRequest.Builder
     */
    private fun applyCommonSettings(builder: CaptureRequest.Builder) {
        val caps = _capabilities.value

        // AE & Manual Exposure / ISO
        if (manualIso != null || manualExposureTimeNs != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualExposureTimeNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            // Auto Exposure mode + Flash configuration
            when (flashMode) {
                FlashMode.OFF -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                FlashMode.AUTO -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                FlashMode.ON -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }
                FlashMode.TORCH -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }
            // Exposure compensation (apply cinema EV if in Cinema mode, or standard exposure index)
            val evToApply = if (currentMode == CameraMode.CINEMA) {
                cinemaConfig.value.exposureCompensation
            } else {
                exposureCompensationIndex
            }
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evToApply)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeLocked)
        }

        // White Balance
        builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode.camera2Mode)

        // Focus
        when (focusMode) {
            FocusMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
            }
            FocusMode.CONTINUOUS -> {
                val mode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }
            FocusMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            FocusMode.MACRO -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
            }
        }

        // Coordinated Hybrid OIS + EIS Stabilization
        val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA ||
                currentMode == CameraMode.DOLLY_ZOOM

        val hybridConfig = _hybridStabilizationConfig.value
        if (isVideoMode) {
            if (isVideoStabilizationEnabled && hybridConfig.isHybridEnabled) {
                // Optical Image Stabilization (Physical hardware gyro compensation)
                if (caps.supportsOis && hybridConfig.isOisPreferred) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                } else {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                }

                // Electronic Image Stabilization (Digital frame margin compensation)
                val isHighFps4k = (_selectedVideoResolution.value?.width ?: 0) >= 3840 && videoFps >= 60
                val allowEis = caps.supportsEis && hybridConfig.isEisPreferred && (!hybridConfig.isAdaptiveFpsLens || !isHighFps4k)

                if (hybridConfig.isUltraStabilizationEnabled) {
                    val chars = getCharacteristics(_selectedLens.value?.cameraId ?: "0")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                        ) == true
                    ) {
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                        )
                    } else {
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                    }
                } else if (allowEis) {
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                } else {
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
            } else {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }
        } else {
            // In Still Photo / Night / Portrait: engage OIS for razor sharp multi-frame images
            if (caps.supportsOis) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }

        // Color profiles & Tonemap
        when (colorProfile) {
            ColorProfile.FLAT_LOG -> {
                if (caps.supportsTonemapCurve) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                }
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            }
            ColorProfile.MONOCHROME -> {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO)
            }
            ColorProfile.VIBRANT -> {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }
            ColorProfile.STANDARD, ColorProfile.NATURAL -> {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
            }
        }

        // Dedicated Cinema Log Color Profile
        if (currentMode == CameraMode.CINEMA) {
            cinemaEngine.applyToCaptureRequest(builder)
        }

        // Digital Zoom / Crop Region
        applyZoom(builder)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val lens = _selectedLens.value ?: return

        val hybridConfig = _hybridStabilizationConfig.value
        val isVideoMode = currentMode == CameraMode.VIDEO || currentMode == CameraMode.CINEMA
        if (hybridConfig.isUltraStabilizationEnabled && isVideoMode && lastStabilizedCrop != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, lastStabilizedCrop)
            return
        }

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            // On Android 11+ (API 30+), CONTROL_ZOOM_RATIO natively switches between physical sensors
            // (e.g. 0.5x Ultra-Wide, 1.0x Main Wide, 3.0x Telephoto) and applies smooth optical/digital scaling
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    val clamped = currentZoom.coerceIn(zoomRange.lower, zoomRange.upper)
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                    return
                }
            }

            // Fallback for legacy devices or SCALER_CROP_REGION
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            val baseRatio = if (lens.baseZoomRatio > 0f) lens.baseZoomRatio else 1.0f
            val effectiveZoom = if (lens.isPhysical && baseRatio > 1.2f) {
                (currentZoom / baseRatio).coerceIn(1.0f, maxZoom)
            } else {
                currentZoom.coerceIn(1.0f, maxZoom)
            }

            val cropW = (sensorRect.width() / effectiveZoom).toInt()
            val cropH = (sensorRect.height() / effectiveZoom).toInt()
            val cropX = (sensorRect.width() - cropW) / 2
            val cropY = (sensorRect.height() - cropH) / 2

            val cropRegion = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting zoom", e)
        }
    }

    /**
     * Updates preview request with new settings on the fly
     */
    fun updatePreviewSettings() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            applyCommonSettings(builder)
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview settings", e)
        }
    }

    /**
     * Change Video HDR Mode (OFF / AUTO / MANUAL)
     */
    fun setVideoHdrMode(mode: VideoHdrMode) {
        videoHdrEngine.mode = mode
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    /**
     * Change Video HDR Manual Intensity (0 to 100)
     */
    fun setVideoHdrManualIntensity(intensity: Int) {
        videoHdrEngine.manualIntensity = intensity
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualShadows(value: Int) {
        videoHdrEngine.manualShadows = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualHighlights(value: Int) {
        videoHdrEngine.manualHighlights = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualContrast(value: Int) {
        videoHdrEngine.manualContrast = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualExposure(value: Int) {
        videoHdrEngine.manualExposure = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualBlackLevel(value: Int) {
        videoHdrEngine.manualBlackLevel = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualMidtones(value: Int) {
        videoHdrEngine.manualMidtones = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    fun setVideoHdrManualSaturation(value: Int) {
        videoHdrEngine.manualSaturation = value
        _videoHdrState.value = videoHdrEngine.currentState
        if (currentMode == CameraMode.VIDEO) {
            updatePreviewSettings()
        }
    }

    /**
     * Set Zoom (.5x to 10x) with seamless automatic lens switching
     */
    fun setZoom(zoom: Float, isPresetTap: Boolean = false) {
        val clampedZoom = zoom.coerceIn(0.5f, 10.0f)
        currentZoom = clampedZoom
        _currentZoom.value = clampedZoom

        val currentLens = _selectedLens.value ?: return

        // If front selfie camera, apply digital zoom on active stream,
        // or switch to rear lens if user explicitly tapped a rear zoom preset (.5x or 1x)
        if (currentLens.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            if (isPresetTap && (clampedZoom < 0.95f || clampedZoom in 0.95f..1.1f)) {
                val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
                val backTarget = backLenses.firstOrNull { if (clampedZoom < 0.95f) it.lensType == LensType.ULTRAWIDE else it.lensType == LensType.WIDE }
                if (backTarget != null) {
                    selectLens(backTarget)
                    return
                }
            }
            updatePreviewSettings()
            return
        }

        // Identify target hardware lens for current zoom level:
        val backLenses = _availableLenses.value.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val targetLens: LensInfo? = when {
            clampedZoom < 0.9f -> {
                // Target is 0.5x Ultra Wide
                backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE && it.isPhysical }
                    ?: backLenses.firstOrNull { it.lensType == LensType.ULTRAWIDE }
            }
            clampedZoom in 0.9f..1.95f -> {
                // Target is 1x Main Wide
                backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE }
            }
            clampedZoom >= 2.95f -> {
                // Target is 3x Telephoto if hardware present, else 2x, else 1x
                backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO_3X && it.isPhysical }
                    ?: backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            }
            clampedZoom >= 1.95f -> {
                // Target is 2x Telephoto if hardware present, else 1x
                backLenses.firstOrNull { it.lensType == LensType.TELEPHOTO && it.isPhysical }
                    ?: backLenses.firstOrNull { it.lensType == LensType.WIDE && !it.isZoomPreset }
            }
            else -> null
        }

        if (targetLens != null && targetLens != currentLens) {
            _selectedLens.value = targetLens
            val isDiffHardware = targetLens.cameraId != currentLens.cameraId ||
                    targetLens.physicalCameraId != currentLens.physicalCameraId
            if (isDiffHardware) {
                zoomDebounceJob?.cancel()
                if (isPresetTap) {
                    // Instant tap on .5, 1x, 2, 3, 10 -> switch hardware lens immediately
                    selectLens(targetLens)
                } else {
                    // Continuous scrubbing: apply optical/digital zoom immediately to active preview,
                    // and switch physical camera ID once scrubbing settles (160ms) to prevent HAL freeze!
                    updatePreviewSettings()
                    zoomDebounceJob = engineScope.launch {
                        delay(160)
                        val activeNow = _selectedLens.value
                        if (targetLens.cameraId != activeNow?.cameraId || targetLens.physicalCameraId != activeNow?.physicalCameraId) {
                            selectLens(targetLens)
                        }
                    }
                }
            } else {
                // Same hardware camera: optical/digital zoom applied immediately without camera restart
                updatePreviewSettings()
            }
        } else {
            // Same lens: apply zoom immediately
            updatePreviewSettings()
        }
    }

    /**
     * Tap to Focus & Meter with optional AE/AF Lock
     * Maps screen normalized tap coordinates to exact SENSOR_INFO_ACTIVE_ARRAY_SIZE coordinates,
     * fully accounting for sensor orientation, front mirror, stream aspect ratio crop, and zoom factor.
     */
    fun triggerFocusAndMeter(normX: Float, normY: Float, isLock: Boolean = false) {
        val lens = _selectedLens.value ?: return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val isFront = (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)

            val sensorW = sensorRect.width().toFloat()
            val sensorH = sensorRect.height().toFloat()

            // 1. Account for Sensor Orientation & Front Camera Mirroring
            // In Android portrait orientation:
            // Rear sensor (90°): X_sensor = normY, Y_sensor = 1 - normX
            // Front sensor (270° with mirror): X_sensor = 1 - normY, Y_sensor = 1 - normX
            val normSensorX = if (isFront || sensorOrientation == 270) {
                (1.0f - normY).coerceIn(0f, 1f)
            } else {
                normY.coerceIn(0f, 1f)
            }
            val normSensorY = (1.0f - normX).coerceIn(0f, 1f)

            // 2. Account for Stream / Viewfinder Aspect Ratio letterbox/pillarbox crop on the 4:3 active sensor array
            val previewW = previewBufferSize.value?.width?.toFloat() ?: 1920f
            val previewH = previewBufferSize.value?.height?.toFloat() ?: 1080f
            val previewAspectLandscape = max(previewW, previewH) / min(previewW, previewH)
            val sensorAspectLandscape = sensorW / sensorH

            val visibleSensorW: Float
            val visibleSensorH: Float
            val cropOffsetX: Float
            val cropOffsetY: Float

            if (previewAspectLandscape > sensorAspectLandscape) {
                // Wide stream (e.g. 16:9 on a 4:3 sensor) -> cropped along sensor height
                visibleSensorW = sensorW
                visibleSensorH = sensorW / previewAspectLandscape
                cropOffsetX = 0f
                cropOffsetY = (sensorH - visibleSensorH) / 2f
            } else {
                // Stream is narrower than or equal to sensor (e.g. 4:3 or 1:1) -> cropped along sensor width
                visibleSensorH = sensorH
                visibleSensorW = sensorH * previewAspectLandscape
                cropOffsetX = (sensorW - visibleSensorW) / 2f
                cropOffsetY = 0f
            }

            // 3. Account for Digital Zoom & Crop Region
            val zoom = _currentZoom.value.coerceAtLeast(1.0f)
            val zoomedW = visibleSensorW / zoom
            val zoomedH = visibleSensorH / zoom
            val zoomedOffsetX = cropOffsetX + (visibleSensorW - zoomedW) / 2f
            val zoomedOffsetY = cropOffsetY + (visibleSensorH - zoomedH) / 2f

            // 4. Exact Sensor Coordinate
            val targetSensorX = sensorRect.left + zoomedOffsetX + normSensorX * zoomedW
            val targetSensorY = sensorRect.top + zoomedOffsetY + normSensorY * zoomedH

            // 5. Metering Rectangle (scaled proportionally to active sensor array)
            val boxW = (sensorW * 0.08f).toInt().coerceAtLeast(120)
            val boxH = (sensorH * 0.08f).toInt().coerceAtLeast(120)

            val left = (targetSensorX - boxW / 2).toInt().coerceIn(sensorRect.left, sensorRect.right - 10)
            val right = (targetSensorX + boxW / 2).toInt().coerceIn(left + 10, sensorRect.right)
            val top = (targetSensorY - boxH / 2).toInt().coerceIn(sensorRect.top, sensorRect.bottom - 10)
            val bottom = (targetSensorY + boxH / 2).toInt().coerceIn(top + 10, sensorRect.bottom)

            val focusRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            if (isLock) {
                isAeLocked = true
                isAfLocked = true
                _isAeLockedFlow.value = true
                _isAfLockedFlow.value = true
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    if (isLock) {
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Tap to focus failed", e)
        }
    }

    fun toggleAeAfLock() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val nextLock = !(isAeLocked || isAfLocked)
        isAeLocked = nextLock
        isAfLocked = nextLock
        _isAeLockedFlow.value = nextLock
        _isAfLockedFlow.value = nextLock

        builder.set(CaptureRequest.CONTROL_AE_LOCK, nextLock)
        if (!nextLock) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle lock", e)
        }
    }

    fun lockDollySubjectAt(normX: Float, normY: Float) {
        val lens = _selectedLens.value ?: return
        val chars = getCharacteristics(lens.cameraId) ?: return
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f
        val lastResult = lastCaptureResult
        val faces = lastResult?.get(CaptureResult.STATISTICS_FACES)
        val diopters = lastResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        dollyZoomEngine.lockSubject(
            normX = normX,
            normY = normY,
            currentZoom = currentZoom,
            faces = faces,
            lensFocusDiopters = diopters,
            sensorRect = sensorRect,
            minZoom = 1.0f,
            maxZoom = maxZoom
        )
    }

    fun calibrateDollyZoom() {
        lockDollySubjectAt(0.5f, 0.5f)
    }

    fun resetDollyZoom() {
        dollyZoomEngine.reset()
    }

    fun setPreviewAspectRatio(ratio: Float) {
        if (ratio > 0f) {
            _previewAspectRatio.value = ratio
        }
    }

    private fun getDeviceRotationDegrees(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: android.view.Surface.ROTATION_0
            } catch (e: Exception) {
                windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        }
        return when (rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun calculateOrientation(sensorOrientation: Int, isFrontFacing: Boolean, deviceRotation: Int): Int {
        return if (isFrontFacing) {
            // Front sensor: (sensorOrientation + deviceRotation) % 360
            (sensorOrientation + deviceRotation) % 360
        } else {
            // Back sensor: (sensorOrientation - deviceRotation + 360) % 360
            (sensorOrientation - deviceRotation + 360) % 360
        }
    }

    private fun getVideoOrientationHint(): Int {
        val lens = _selectedLens.value ?: return 90
        val isFront = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = try {
            val chars = getCharacteristics(lens.cameraId)
            chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
        } catch (e: Exception) {
            if (isFront) 270 else 90
        }
        val deviceRotation = getDeviceRotationDegrees()
        val standardHint = calculateOrientation(sensorOrientation, isFront, deviceRotation)

        // When "Save selfie as previewed without flipping" is enabled:
        // In the viewfinder preview, front camera is mirrored horizontally (scale -1, 1).
        // Standard video container players play according to orientation hint.
        // For front camera in portrait (deviceRotation = 0, sensor = 270):
        // Standard hint is 270°. Some players or sensors inverted this to 90° (upside-down by 180°).
        // Returning standardHint properly prevents upside down playback.
        return standardHint
    }

    private fun getCaptureJpegOrientation(): Int {
        val lens = _selectedLens.value ?: return 90
        val isFront = lens.facing == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = try {
            val chars = getCharacteristics(lens.cameraId)
            chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
        } catch (e: Exception) {
            if (isFront) 270 else 90
        }
        val deviceRotation = getDeviceRotationDegrees()
        return calculateOrientation(sensorOrientation, isFront, deviceRotation)
    }

    /**
     * Upgraded Computational Night Mode Multi-Frame Capture & Fusion.
     * Captures multiple aligned burst frames across 1-5 seconds, reduces hand-shake ghosting,
     * boosts signal-to-noise ratio by temporal averaging, and applies adaptive tone mapping.
     */
    fun takeNightPhoto(
        durationSeconds: Int = 2,
        isAntiGhosting: Boolean = true,
        noiseSuppression: Float = 0.85f,
        shadowLift: Float = 1.25f,
        onProgress: (NightCaptureProgress) -> Unit = {},
        onComplete: (Uri?) -> Unit
    ) {
        val camera = cameraDevice ?: run {
            onComplete(null)
            return
        }
        val session = captureSession ?: run {
            onComplete(null)
            return
        }
        val readerJpeg = imageReaderJpeg ?: run {
            onComplete(null)
            return
        }

        _isCapturing.value = true
        val targetFrameCount = (durationSeconds * 3).coerceIn(4, 12)
        val collectedBitmaps = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())
        val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        val initialProgress = NightCaptureProgress(
            isCapturing = true,
            remainingSeconds = durationSeconds.toFloat(),
            progress = 0.05f,
            statusText = "Hold device steady... Capturing burst"
        )
        _nightProgress.value = initialProgress
        onProgress(initialProgress)

        // Countdown timer job
        val countdownJob = engineScope.launch {
            val totalMs = durationSeconds * 1000L
            val stepMs = 100L
            var elapsedMs = 0L
            while (elapsedMs < totalMs && !isCompleted.get()) {
                delay(stepMs)
                elapsedMs += stepMs
                val remSec = max(0f, (totalMs - elapsedMs) / 1000f)
                val prog = (elapsedMs.toFloat() / totalMs * 0.5f).coerceIn(0.05f, 0.5f)
                val status = "Hold device steady (${collectedBitmaps.size}/$targetFrameCount frames)"
                val current = NightCaptureProgress(
                    isCapturing = true,
                    remainingSeconds = remSec,
                    progress = prog,
                    statusText = status
                )
                _nightProgress.value = current
                withContext(Dispatchers.Main) { onProgress(current) }
            }
        }

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    collectedBitmaps.add(bmp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error acquiring night burst frame", e)
            } finally {
                image.close()
            }

            if (collectedBitmaps.size >= targetFrameCount && isCompleted.compareAndSet(false, true)) {
                countdownJob.cancel()
                finalizeNightCapture(
                    collectedBitmaps,
                    noiseSuppression,
                    shadowLift,
                    isAntiGhosting,
                    onProgress,
                    onComplete
                )
            }
        }, backgroundHandler)

        try {
            val requests = mutableListOf<CaptureRequest>()
            val orientation = getCaptureJpegOrientation()
            for (i in 0 until targetFrameCount) {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(readerJpeg.surface)
                applyCommonSettings(builder)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                requests.add(builder.build())
            }
            session.captureBurst(requests, null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit night burst requests", e)
            isCompleted.set(true)
            countdownJob.cancel()
            _isCapturing.value = false
            _nightProgress.value = NightCaptureProgress()
            onComplete(null)
        }
    }

    private fun finalizeNightCapture(
        frames: List<Bitmap>,
        noiseSuppression: Float,
        shadowLift: Float,
        isAntiGhosting: Boolean,
        onProgress: (NightCaptureProgress) -> Unit,
        onComplete: (Uri?) -> Unit
    ) {
        engineScope.launch(Dispatchers.Default) {
            val progressUpdate: (Float) -> Unit = { p ->
                val overall = 0.5f + (p * 0.45f)
                val status = if (p < 0.5f) "Aligning Frames & Anti-Ghosting..." else "Adaptive Tone Mapping..."
                val state = NightCaptureProgress(
                    isCapturing = true,
                    remainingSeconds = 0f,
                    progress = overall,
                    statusText = status
                )
                _nightProgress.value = state
                runBlocking(Dispatchers.Main) { onProgress(state) }
            }

            val fusedBitmap = try {
                nightFusionProcessor.processNightFrames(
                    frames = frames,
                    noiseSuppression = noiseSuppression,
                    shadowLift = shadowLift,
                    isAntiGhostingEnabled = isAntiGhosting,
                    onProgress = progressUpdate
                )
            } catch (e: Exception) {
                Log.e(TAG, "Night fusion failed, using base frame", e)
                frames.firstOrNull() ?: Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            }

            val uri = saveBitmapToMediaStore(fusedBitmap, 0)

            frames.forEach { if (!it.isRecycled && it != fusedBitmap) it.recycle() }
            if (!fusedBitmap.isRecycled) fusedBitmap.recycle()

            _isCapturing.value = false
            val finalProgress = NightCaptureProgress(
                isCapturing = false,
                remainingSeconds = 0f,
                progress = 1.0f,
                statusText = "Completed"
            )
            _nightProgress.value = finalProgress
            updateStorageStats()
            withContext(Dispatchers.Main) {
                onProgress(finalProgress)
                onComplete(uri)
            }
        }
    }

    /**
     * Take still photo (JPEG + optional RAW). In Super Resolution mode (50M/100M/200M), triggers Real-ESRGAN capture.
     */
    fun takePhoto(onComplete: (Uri?) -> Unit) {
        if (photoMegapixelMode.isSuperResolution) {
            takePhotoRealEsrgan(photoMegapixelMode, onComplete)
            return
        }

        if (isRefocusPhotoEnabled && currentMode == CameraMode.PHOTO) {
            takePhotoRefocus(onComplete)
            return
        }

        if (isAiZoomEnabled && currentMode == CameraMode.PHOTO) {
            takePhotoAiZoom(onComplete)
            return
        }

        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val readerJpeg = imageReaderJpeg ?: return

        _isCapturing.value = true

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(readerJpeg.surface)

            val caps = _capabilities.value
            val isRaw = isRawCaptureEnabled && caps.supportsRaw && imageReaderRaw != null
            if (isRaw) {
                imageReaderRaw?.surface?.let { captureBuilder.addTarget(it) }
            }

            applyCommonSettings(captureBuilder)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())

            readerJpeg.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    engineScope.launch(Dispatchers.IO) {
                        val uri = saveJpegToMediaStore(image)
                        image.close()
                        _isCapturing.value = false
                        updateStorageStats()
                        withContext(Dispatchers.Main) {
                            onComplete(uri)
                        }
                    }
                }
            }, backgroundHandler)

            if (isRaw) {
                imageReaderRaw?.setOnImageAvailableListener({ reader ->
                    val rawImage = reader.acquireLatestImage()
                    if (rawImage != null) {
                        engineScope.launch(Dispatchers.IO) {
                            val lens = _selectedLens.value
                            if (lens != null) {
                                val characteristics = getCharacteristics(lens.cameraId)
                                if (characteristics != null) {
                                    saveRawToMediaStore(rawImage, characteristics)
                                }
                            }
                            rawImage.close()
                        }
                    }
                }, backgroundHandler)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Photo capture completed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo", e)
            _isCapturing.value = false
            onComplete(null)
        }
    }

    /**
     * Refocus Photo Capture:
     * When Refocus Photo is enabled, captures a short sequence of 3 focus planes:
     * Near (macro/foreground) -> Mid (subject/user focus) -> Far (background/infinity).
     *
     * - The subject/mid plane is immediately saved and presented as the normal photo preview
     *   to ensure zero shutter lag or preview stall.
     * - Sequential/tiled depth map generation and permanent bundling is handled in the
     *   background thread without keeping all full-resolution frames in RAM simultaneously.
     * - If burst capture or refocus processing fails, it automatically falls back to saving
     *   the normal photo without failing capture.
     */
    private fun takePhotoRefocus(onComplete: (Uri?) -> Unit) {
        val camera = cameraDevice
        val session = captureSession
        val readerJpeg = imageReaderJpeg
        if (camera == null || session == null || readerJpeg == null) {
            takePhoto(onComplete)
            return
        }

        _isCapturing.value = true

        val lens = _selectedLens.value
        val chars = if (lens != null) getCharacteristics(lens.cameraId) else null
        val minFocusDist = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // If camera lens is fixed-focus (minFocusDist <= 0.05f), it cannot physically shift focus planes,
        // so fallback to standard single photo capture safely.
        if (minFocusDist <= 0.05f) {
            takePhoto(onComplete)
            return
        }

        val lastFocusDiopters = lastCaptureResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)
            ?: (minFocusDist * 0.35f)

        // Focus planes: Near -> Mid (Subject) -> Far
        val nearDiopters = (lastFocusDiopters + minFocusDist * 0.35f).coerceIn(0.1f, minFocusDist)
        val midDiopters = lastFocusDiopters.coerceIn(0f, minFocusDist)
        val farDiopters = (lastFocusDiopters * 0.25f).coerceIn(0f, minFocusDist)

        val baseCache = context.cacheDir ?: context.filesDir
        if (!baseCache.exists()) {
            try { baseCache.mkdirs() } catch (ignored: Exception) {}
        }
        val tempNearFile = File(baseCache, "refocus_tmp_near_${System.currentTimeMillis()}.jpg")
        val tempMidFile = File(baseCache, "refocus_tmp_mid_${System.currentTimeMillis()}.jpg")
        val tempFarFile = File(baseCache, "refocus_tmp_far_${System.currentTimeMillis()}.jpg")
        tempNearFile.parentFile?.mkdirs()

        var savedNormalUri: Uri? = null
        var framesReceived = 0
        val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close() // Close Image immediately to free Camera2 buffer pool

            val frameIndex = synchronized(this) { framesReceived++ }
            when (frameIndex) {
                0 -> {
                    // Near plane
                    try { tempNearFile.writeBytes(bytes) } catch (e: Exception) { Log.w(TAG, "Failed writing tempNear", e) }
                }
                1 -> {
                    // Mid plane (Subject) - save immediately as normal photo
                    try { tempMidFile.writeBytes(bytes) } catch (e: Exception) { Log.w(TAG, "Failed writing tempMid", e) }
                    engineScope.launch(Dispatchers.IO) {
                        try {
                            val uri = saveJpegBytesToMediaStore(bytes)
                            savedNormalUri = uri
                            _isCapturing.value = false
                            updateStorageStats()
                            if (isCompleted.compareAndSet(false, true)) {
                                withContext(Dispatchers.Main) {
                                    onComplete(uri)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving mid plane JPEG", e)
                        }
                    }
                }
                2 -> {
                    // Far plane
                    try { tempFarFile.writeBytes(bytes) } catch (e: Exception) { Log.w(TAG, "Failed writing tempFar", e) }
                    // Reset listener
                    readerJpeg.setOnImageAvailableListener(null, null)

                    // Launch background Refocus bundle processing
                    engineScope.launch(Dispatchers.Default) {
                        val uri = savedNormalUri
                        if (uri != null) {
                            refocusEngine.processAndPersist(
                                photoUri = uri,
                                tempNearFile = tempNearFile,
                                tempMidFile = tempMidFile,
                                tempFarFile = tempFarFile,
                                nearDiopters = nearDiopters,
                                midDiopters = midDiopters,
                                farDiopters = farDiopters
                            )
                        } else {
                            tempNearFile.delete()
                            tempMidFile.delete()
                            tempFarFile.delete()
                        }
                    }
                }
                else -> {
                    // Extra frame if any
                }
            }
        }, backgroundHandler)

        try {
            val requests = mutableListOf<CaptureRequest>()

            // Plane 1: Near
            val reqNear = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerJpeg.surface)
                applyCommonSettings(this)
                set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, nearDiopters)
            }
            requests.add(reqNear.build())

            // Plane 2: Mid (Subject)
            val reqMid = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerJpeg.surface)
                applyCommonSettings(this)
                set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, midDiopters)
            }
            requests.add(reqMid.build())

            // Plane 3: Far
            val reqFar = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerJpeg.surface)
                applyCommonSettings(this)
                set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, farDiopters)
            }
            requests.add(reqFar.build())

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Refocus burst plane completed")
                }
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.w(TAG, "Refocus burst plane failed: ${failure.reason}")
                }
            }, backgroundHandler)

            // Watchdog fallback: in case burst fails or frame 1 is missed, ensure normal photo is saved
            engineScope.launch {
                delay(3500)
                if (isCompleted.compareAndSet(false, true)) {
                    Log.w(TAG, "Refocus watchdog triggered fallback")
                    readerJpeg.setOnImageAvailableListener(null, null)
                    _isCapturing.value = false
                    // If we got at least one frame saved in temp files, save it as fallback
                    var fallbackUri: Uri? = null
                    val candidate = if (tempMidFile.exists() && tempMidFile.length() > 0) tempMidFile
                        else if (tempNearFile.exists() && tempNearFile.length() > 0) tempNearFile
                        else if (tempFarFile.exists() && tempFarFile.length() > 0) tempFarFile else null
                    if (candidate != null) {
                        try {
                            fallbackUri = saveJpegBytesToMediaStore(candidate.readBytes())
                        } catch (e: Exception) { Log.e(TAG, "Fallback save error", e) }
                    }
                    tempNearFile.delete(); tempMidFile.delete(); tempFarFile.delete()
                    withContext(Dispatchers.Main) {
                        onComplete(fallbackUri)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed submitting refocus burst, falling back to standard capture", e)
            tempNearFile.delete(); tempMidFile.delete(); tempFarFile.delete()
            takePhoto(onComplete)
        }
    }

    /**
     * AI Zoom Capture using Deep Burst Super-Resolution (DBSR).
     *
     * Captures a rapid burst of frames (preferring RAW Bayer sensor frames when supported)
     * with fixed AE/AWB/focus locks to prevent inter-frame exposure/color drift.
     * The first frame is immediately saved and returned to UI to guarantee instant user feedback.
     * DBSR optical flow alignment (PWC-Net), residual feature encoding, attention merging, and
     * 4x sub-pixel super-resolution are executed asynchronously in the background.
     */
    private fun takePhotoAiZoom(onComplete: (Uri?) -> Unit) {
        val camera = cameraDevice
        val session = captureSession
        val readerJpeg = imageReaderJpeg
        if (camera == null || session == null || readerJpeg == null) {
            takePhoto(onComplete)
            return
        }

        _isCapturing.value = true
        val quality = aiZoomQuality
        val burstCount = quality.burstCount
        val caps = _capabilities.value
        val hasRaw = caps.supportsRaw && imageReaderRaw != null
        val readerRaw = imageReaderRaw

        val rotationDeg = getCaptureJpegOrientation()

        val capturedTensors = mutableListOf<com.example.camera.dbsr.Tensor>()
        val isFirstSaved = java.util.concurrent.atomic.AtomicBoolean(false)
        val isProcessingLaunched = java.util.concurrent.atomic.AtomicBoolean(false)
        var baseBitmap: Bitmap? = null
        var baseUri: Uri? = null
        var firstBytes: ByteArray? = null
        var targetW = 80
        var targetH = 60

        readerJpeg.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            engineScope.launch(Dispatchers.IO) {
                // Instantly save base frame to MediaStore so user UI never freezes
                if (isFirstSaved.compareAndSet(false, true)) {
                    firstBytes = bytes
                    val uri = saveJpegBytesToMediaStore(bytes)
                    baseUri = uri
                    _isCapturing.value = false
                    withContext(Dispatchers.Main) {
                        onComplete(uri)
                    }

                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        baseBitmap = bmp
                        val dims = dbsrEngine.calculateTargetProcessingDimensions(bmp.width, bmp.height, quality)
                        targetW = dims.first
                        targetH = dims.second
                        val tensor = dbsrEngine.extractTensorFromBitmap(bmp, targetW, targetH)
                        synchronized(capturedTensors) {
                            capturedTensors.add(tensor)
                        }
                    }
                } else if (!hasRaw) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        val tensor = dbsrEngine.extractTensorFromBitmap(bmp, targetW, targetH)
                        synchronized(capturedTensors) {
                            capturedTensors.add(tensor)
                        }
                        bmp.recycle()
                    }
                }

                val currentTotal = synchronized(capturedTensors) { capturedTensors.size }
                if (currentTotal >= burstCount && isProcessingLaunched.compareAndSet(false, true)) {
                    val baseBmp = baseBitmap
                    if (baseBmp != null) {
                        launchDbsrProcessing(capturedTensors, baseBmp, baseUri, firstBytes, quality)
                    }
                }
            }
        }, backgroundHandler)

        if (hasRaw && readerRaw != null) {
            readerRaw.setOnImageAvailableListener({ reader ->
                val rawImage = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                engineScope.launch(Dispatchers.IO) {
                    try {
                        val rawTensor = dbsrEngine.extractBayerTensorFromRaw(rawImage, targetW, targetH)
                        synchronized(capturedTensors) {
                            capturedTensors.add(rawTensor)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting RAW Bayer tensor", e)
                    } finally {
                        rawImage.close()
                    }

                    val currentTotal = synchronized(capturedTensors) { capturedTensors.size }
                    if (currentTotal >= burstCount && isProcessingLaunched.compareAndSet(false, true)) {
                        val baseBmp = baseBitmap
                        if (baseBmp != null) {
                            launchDbsrProcessing(capturedTensors, baseBmp, baseUri, firstBytes, quality)
                        }
                    }
                }
            }, backgroundHandler)
        }

        try {
            val requests = mutableListOf<CaptureRequest>()
            for (i in 0 until burstCount) {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyCommonSettings(builder)

                // Reuse camera AE, AWB, and focus state across the burst
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                builder.set(CaptureRequest.JPEG_ORIENTATION, rotationDeg)
                builder.set(CaptureRequest.JPEG_QUALITY, 98.toByte())

                builder.addTarget(readerJpeg.surface)
                if (hasRaw && readerRaw != null) {
                    builder.addTarget(readerRaw.surface)
                }
                requests.add(builder.build())
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "AI Zoom burst frame completed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error executing AI Zoom capture burst", e)
            _isCapturing.value = false
            takePhoto(onComplete)
        }
    }

    private fun launchDbsrProcessing(
        burstTensors: List<com.example.camera.dbsr.Tensor>,
        baseBitmap: Bitmap,
        baseUri: Uri?,
        originalBytes: ByteArray?,
        quality: com.example.camera.dbsr.AiZoomQuality
    ) {
        engineScope.launch(Dispatchers.Default) {
            try {
                _isAiZoomProcessing.value = true
                _aiZoomProgress.value = 0.05f

                val frames = burstTensors.take(quality.burstCount)

                val enhancedBitmap = dbsrEngine.processBurst(
                    burstFrames = frames,
                    baseBitmap = baseBitmap,
                    quality = quality,
                    onProgress = { p -> _aiZoomProgress.value = p }
                )

                val finalUri = dbsrEngine.saveAiZoomImage(
                    context = context,
                    bitmap = enhancedBitmap,
                    baseUri = baseUri,
                    originalBytes = originalBytes
                )

                enhancedBitmap.recycle()
                baseBitmap.recycle()

                if (finalUri != null) {
                    _lastCapturedMedia.value = CapturedMedia(
                        uri = finalUri,
                        isVideo = false,
                        timestamp = System.currentTimeMillis(),
                        displayName = "AI Zoom Photo"
                    )
                }

                updateStorageStats()
                _isAiZoomProcessing.value = false
                _aiZoomProgress.value = 1.0f

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "AI Zoom (DBSR) complete", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "DBSR enhancement failed, preserved base photo", e)
                _isAiZoomProcessing.value = false
            }
        }
    }

    /**
     * Real-ESRGAN AI Super Resolution capture (50M, 100M, 200M):
     * Captures a single native-resolution frame with maximum optical sharpness, optimal sensor settings,
     * and native orientation/EXIF.
     * The raw frame is stored as a pending file and returned to the UI so the user can tap the thumbnail
     * to launch the official xinntao/Real-ESRGAN tile-based GPU super-resolution pipeline.
     */
    /**
     * Obtains and guarantees existence of the temporary working directory for Real-ESRGAN frames.
     * Checks multiple candidates (internal cacheDir, externalCacheDir, filesDir) and ensures mkdirs() succeeds.
     */
    fun getRealEsrganWorkingDir(): File {
        val candidates = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
            context.filesDir
        )
        for (candidate in candidates) {
            try {
                if (!candidate.exists()) {
                    candidate.mkdirs()
                }
                val esrganSubdir = File(candidate, "pending_esrgan")
                if (!esrganSubdir.exists()) {
                    val created = esrganSubdir.mkdirs()
                    if (created || esrganSubdir.exists()) {
                        return esrganSubdir
                    }
                } else if (esrganSubdir.canWrite()) {
                    return esrganSubdir
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not use candidate cache directory: ${candidate.absolutePath}", e)
            }
        }
        // Direct fallback: ensure filesDir/pending_esrgan exists
        val fallbackDir = File(context.filesDir, "pending_esrgan")
        try { fallbackDir.mkdirs() } catch (ignored: Exception) {}
        return fallbackDir
    }

    private fun getRealEsrganTempFile(): File {
        val workingDir = getRealEsrganWorkingDir()
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }
        val timestamp = System.currentTimeMillis()
        val nano = System.nanoTime()
        val tempFile = File(workingDir, "pending_esrgan_${timestamp}_$nano.jpg")
        val parent = tempFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return tempFile
    }

    /**
     * Captures a high-resolution base frame for Real-ESRGAN super-resolution processing.
     * The raw frame is stored as a pending file and returned to the UI so the user can tap the thumbnail
     * or have it auto-processed by the official xinntao/Real-ESRGAN tile-based GPU super-resolution pipeline.
     */
    fun takePhotoRealEsrgan(mode: PhotoMegapixelMode, onComplete: (Uri?) -> Unit) {
        val camera = cameraDevice ?: run {
            onComplete(null)
            return
        }
        val session = captureSession ?: run {
            onComplete(null)
            return
        }
        val readerJpeg = imageReaderJpeg ?: run {
            onComplete(null)
            return
        }

        // Verify and ensure working directory exists before capture
        val workingDir = getRealEsrganWorkingDir()
        if (!workingDir.exists()) {
            val created = workingDir.mkdirs()
            if (!created && !workingDir.exists()) {
                Log.e(TAG, "Cannot create working directory for Real-ESRGAN: ${workingDir.absolutePath}")
                onComplete(null)
                return
            }
        }

        _isCapturing.value = true
        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(readerJpeg.surface)
            applyCommonSettings(captureBuilder)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val chars = getCharacteristics(camera.id)
                    val sensorCaps = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if (sensorCaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                        captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware sensor ultra-high resolution mode not applicable", e)
                }
            }

            var frameProcessed = false

            readerJpeg.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (frameProcessed) {
                    try { image.close() } catch (ignored: Exception) {}
                    return@setOnImageAvailableListener
                }
                frameProcessed = true
                readerJpeg.setOnImageAvailableListener(null, null)

                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    // Ensure maximum photographic quality with zero downsampling
                    val options = BitmapFactory.Options().apply {
                        inMutable = true
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val rawBitmap = try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error decoding raw frame for Real-ESRGAN", t)
                        null
                    }

                    if (rawBitmap != null) {
                        val exif = try {
                            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                        } catch (e: Exception) {
                            null
                        }
                        val exifOrientation = exif?.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_UNDEFINED
                        ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                        val matrix = Matrix()
                        when (exifOrientation) {
                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            else -> {
                                if (rawBitmap.width > rawBitmap.height) {
                                    val rot = if (isFrontFacing) 270f else 90f
                                    matrix.postRotate(rot)
                                }
                            }
                        }

                        if (isFrontFacing && saveSelfieAsPreviewed) {
                            matrix.postScale(-1f, 1f)
                        }

                        val uprightBitmap = try {
                            if (!matrix.isIdentity) {
                                val rotated = Bitmap.createBitmap(
                                    rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                )
                                if (rotated != rawBitmap) {
                                    rawBitmap.recycle()
                                }
                                rotated
                            } else {
                                rawBitmap
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed rotating frame, using unrotated bitmap", t)
                            rawBitmap
                        }

                        engineScope.launch(Dispatchers.IO) {
                            var tempFile: File? = null
                            try {
                                tempFile = getRealEsrganTempFile()
                                val parentDir = tempFile.parentFile
                                if (parentDir != null && !parentDir.exists()) {
                                    parentDir.mkdirs()
                                }

                                java.io.FileOutputStream(tempFile).use { fos ->
                                    val compressed = uprightBitmap.compress(Bitmap.CompressFormat.JPEG, 98, fos)
                                    if (!compressed) {
                                        throw java.io.IOException("Bitmap JPEG compression failed for Real-ESRGAN pending frame")
                                    }
                                    fos.flush()
                                    try {
                                        fos.fd.sync()
                                    } catch (ignored: Exception) {}
                                }

                                if (!tempFile.exists() || tempFile.length() == 0L) {
                                    throw java.io.IOException("Pending ESRGAN file was not written or is 0 bytes: ${tempFile.absolutePath}")
                                }

                                Log.d(TAG, "Real-ESRGAN pending base frame written successfully: ${tempFile.absolutePath} (${tempFile.length()} bytes)")

                                val pendingUri = Uri.fromFile(tempFile)
                                val captured = CapturedMedia(
                                    uri = pendingUri,
                                    isVideo = false,
                                    timestamp = System.currentTimeMillis(),
                                    displayName = "${mode.label} AI (Processing...)",
                                    isPendingAiProcessing = true,
                                    aiResolutionMode = mode,
                                    pendingRawFilePath = tempFile.absolutePath
                                )
                                _lastCapturedMedia.value = captured
                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onComplete(pendingUri)
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "Error writing temporary Real-ESRGAN base frame to ${tempFile?.absolutePath}", t)
                                try {
                                    tempFile?.delete()
                                } catch (ignored: Exception) {}
                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onComplete(null)
                                }
                            } finally {
                                if (!uprightBitmap.isRecycled) {
                                    uprightBitmap.recycle()
                                }
                            }
                        }
                    } else {
                        _isCapturing.value = false
                        engineScope.launch(Dispatchers.Main) {
                            onComplete(null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring Real-ESRGAN base frame", e)
                    try { image.close() } catch (ignored: Exception) {}
                    _isCapturing.value = false
                    engineScope.launch(Dispatchers.Main) {
                        onComplete(null)
                    }
                }
            }, backgroundHandler)

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Real-ESRGAN frame capture completed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking Real-ESRGAN photo", e)
            _isCapturing.value = false
            onComplete(null)
        }
    }

    /**
     * Captures a single full-resolution uncompressed Bitmap for Portrait AI processing.
     * Ensures orientation and left/right mirroring match the viewfinder exactly.
     */
    fun captureStillBitmap(onBitmapCaptured: (Bitmap?) -> Unit) {
        val camera = cameraDevice ?: run {
            onBitmapCaptured(null)
            return
        }
        val session = captureSession ?: run {
            onBitmapCaptured(null)
            return
        }
        val readerJpeg = imageReaderJpeg ?: run {
            onBitmapCaptured(null)
            return
        }

        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        _isCapturing.value = true

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(readerJpeg.surface)
            applyCommonSettings(captureBuilder)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCaptureJpegOrientation())
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

            readerJpeg.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    engineScope.launch(Dispatchers.IO) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            val options = BitmapFactory.Options().apply {
                                inMutable = true
                            }
                            val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                            if (rawBitmap != null) {
                                val exif = try {
                                    android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                                } catch (e: Exception) {
                                    null
                                }
                                val exifOrientation = exif?.getAttributeInt(
                                    android.media.ExifInterface.TAG_ORIENTATION,
                                    android.media.ExifInterface.ORIENTATION_UNDEFINED
                                ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                                val matrix = Matrix()
                                when (exifOrientation) {
                                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                                    else -> {
                                        if (rawBitmap.width > rawBitmap.height) {
                                            val rot = if (isFrontFacing) 270f else 90f
                                            matrix.postRotate(rot)
                                        }
                                    }
                                }

                                // Front camera viewfinder WYSIWYG mirroring preservation
                                if (isFrontFacing && saveSelfieAsPreviewed) {
                                    matrix.postScale(-1f, 1f)
                                }

                                val orientedBitmap = if (!matrix.isIdentity) {
                                    val transformed = Bitmap.createBitmap(
                                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                    )
                                    if (transformed != rawBitmap) {
                                        rawBitmap.recycle()
                                    }
                                    transformed
                                } else {
                                    rawBitmap
                                }

                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onBitmapCaptured(orientedBitmap)
                                }
                            } else {
                                _isCapturing.value = false
                                withContext(Dispatchers.Main) {
                                    onBitmapCaptured(null)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding and orienting captured still bitmap", e)
                            try { image.close() } catch (ignored: Exception) {}
                            _isCapturing.value = false
                            withContext(Dispatchers.Main) {
                                onBitmapCaptured(null)
                            }
                        }
                    }
                }
            }, backgroundHandler)

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    Log.d(TAG, "Portrait still frame capture triggered")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing still bitmap for portrait", e)
            _isCapturing.value = false
            onBitmapCaptured(null)
        }
    }

    /**
     * Start Video Recording
     */
    fun startVideoRecording(onError: (String) -> Unit) {
        if (_isRecordingVideo.value) return

        val camera = cameraDevice ?: return
        val lens = _selectedLens.value ?: return

        try {
            closeCameraCaptureSession()

            val isCinema = (currentMode == CameraMode.CINEMA)
            val videoRes = if (isCinema && cinemaConfig.value.selectedResolution != null) {
                cinemaConfig.value.selectedResolution!!
            } else {
                _selectedVideoResolution.value ?: CameraResolution(1920, 1080)
            }
            val is10BitRequested = isCinema && cinemaConfig.value.logBitDepth == LogBitDepth.BIT_10 && cinemaCapabilities.value.supports10BitRecording
            val bitrate = if (isCinema) {
                when {
                    videoRes.width >= 3840 -> 100_000_000
                    videoRes.width >= 1920 -> 60_000_000
                    else -> 30_000_000
                }
            } else if (is10BitRequested) {
                when {
                    videoRes.width >= 3840 -> 75_000_000
                    videoRes.width >= 1920 -> 40_000_000
                    else -> 20_000_000
                }
            } else if (videoBitrateOption.bps > 0) {
                videoBitrateOption.bps
            } else {
                when {
                    videoRes.width >= 3840 -> 40_000_000
                    videoRes.width >= 1920 -> 20_000_000
                    else -> 10_000_000
                }
            }
            val targetFps = if (isCinema) cinemaConfig.value.videoFps else videoFps
            val cinemaCodec = if (isCinema) cinemaConfig.value.codec else CinemaCodec.H264
            val isSoftwareCinema = isCinema && (cinemaCodec == CinemaCodec.PRORES || cinemaCodec == CinemaCodec.VP9)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix = if (isCinema) "CINEMA_" else "VID_"
            val extension = if (isSoftwareCinema && cinemaCodec == CinemaCodec.VP9) "webm" else "mp4"
            val mimeType = if (extension == "webm") "video/webm" else "video/mp4"
            val fileName = "${prefix}$timeStamp.$extension"

            currentVideoFileName = fileName
            currentVideoMimeType = mimeType

            // 1. Ensure directory exists and create dedicated temporary recording file
            val recordingDir = context.externalCacheDir ?: context.cacheDir
            recordingDir.mkdirs()
            val tempFileName = if (isCinema) {
                "cinema_temp_${System.currentTimeMillis()}.$extension"
            } else {
                "rec_temp_${System.currentTimeMillis()}.$extension"
            }
            val tempFile = File(recordingDir, tempFileName).apply {
                if (exists()) delete()
                createNewFile()
            }
            currentRecordingTempFile = tempFile

            // Software Codec Check for Cinema Mode (VP9 & Apple ProRes 422 10-bit)
            if (isSoftwareCinema) {
                isSoftwareCinemaRecording = true

                val orientationHint = getVideoOrientationHint()
                val recorderSurface = cinemaSoftwareRecorder.startRecording(
                    destFile = tempFile,
                    width = videoRes.width,
                    height = videoRes.height,
                    fps = targetFps,
                    bitrate = bitrate,
                    codec = cinemaCodec,
                    bitDepth = if (is10BitRequested || cinemaCodec == CinemaCodec.PRORES) LogBitDepth.BIT_10 else LogBitDepth.BIT_8,
                    isAudioEnabled = isAudioEnabled,
                    orientationHint = orientationHint
                )
                val previewSurf = previewSurface ?: return
                val surfaces = listOf(previewSurf, recorderSurface)

                previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurf)
                    addTarget(recorderSurface)
                    applyCommonSettings(this)
                }

                camera.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                previewRequestBuilder?.let {
                                    session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                                }
                                _isRecordingVideo.value = true
                                startVideoTimer()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start Software Cinema recording session", e)
                                val errorDetail = getMediaCodecErrorDetail(e)
                                onError("Cinema recording session error: $errorDetail")
                                stopVideoRecording()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Failed to configure Software Cinema recording capture session")
                            isSoftwareCinemaRecording = false
                            try {
                                cinemaSoftwareRecorder.stopRecording()
                            } catch (ignored: Exception) {}
                            currentRecordingTempFile?.let { f ->
                                try { f.delete() } catch (ignored: Exception) {}
                                currentRecordingTempFile = null
                            }
                            onError("Failed to configure cinema recording capture session")
                        }
                    },
                    backgroundHandler
                )
                return
            }

            // Hardware Recording (MediaRecorder for H.264 & H.265 / HEVC)
            @Suppress("DEPRECATION")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }.apply {
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder runtime error: what=$what extra=$extra")
                    onError("Hardware recording error (code=$what, extra=$extra)")
                }
                if (isAudioEnabled) {
                    try {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioSource.MIC not available, continuing without audio", e)
                    }
                }
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                // Passing ParcelFileDescriptor avoids permission/ENOENT issues in cameraserver process
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE)
                videoRecordingFileDescriptor = pfd
                setOutputFile(pfd.fileDescriptor)

                setVideoEncodingBitRate(bitrate)
                setVideoFrameRate(targetFps)
                setVideoSize(videoRes.width, videoRes.height)
                setVideoEncodingBitRate(bitrate)
                setVideoFrameRate(targetFps)
                setVideoSize(videoRes.width, videoRes.height)

                val useHevc = (cinemaCodec == CinemaCodec.H265) || is10BitRequested
                if (useHevc) {
                    setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                    if (is10BitRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            setVideoEncodingProfileLevel(
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "HEVC Main 10 Tier 5.1 profile level unsupported, trying Main profile", e)
                            try {
                                setVideoEncodingProfileLevel(
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                                )
                            } catch (ignored: Exception) {}
                        }
                    }
                } else {
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                }

                if (isAudioEnabled) {
                    try {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(48000)
                        setAudioEncodingBitRate(192000)
                    } catch (ignored: Exception) {}
                }

                val orientationHint = getVideoOrientationHint()
                setOrientationHint(orientationHint)
                Log.d(TAG, "MediaRecorder configured with output=${tempFile.absolutePath}, codec=${if (useHevc) "HEVC" else "H264"}")

                try {
                    prepare()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder prepare failed with primary settings, trying baseline fallback", e)
                    if (useHevc) {
                        try {
                            reset()
                            if (isAudioEnabled) {
                                try { setAudioSource(MediaRecorder.AudioSource.MIC) } catch (ignored: Exception) {}
                            }
                            setVideoSource(MediaRecorder.VideoSource.SURFACE)
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setOutputFile(tempFile.absolutePath)
                            setVideoEncodingBitRate(minOf(bitrate, 40_000_000))
                            setVideoFrameRate(targetFps)
                            setVideoSize(videoRes.width, videoRes.height)
                            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                            if (isAudioEnabled) {
                                try {
                                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                    setAudioSamplingRate(48000)
                                    setAudioEncodingBitRate(192000)
                                } catch (ignored: Exception) {}
                            }
                            setOrientationHint(orientationHint)
                            prepare()
                            Log.i(TAG, "MediaRecorder fallback prepare succeeded with H.264")
                        } catch (fallbackError: Exception) {
                            val detail = getMediaCodecErrorDetail(fallbackError)
                            throw IllegalStateException("Hardware video encoder failed: $detail", fallbackError)
                        }
                    } else {
                        val detail = getMediaCodecErrorDetail(e)
                        throw IllegalStateException("Hardware video encoder failed: $detail", e)
                    }
                }
            }

            val videoRatio = max(videoRes.width, videoRes.height).toFloat() / min(videoRes.width, videoRes.height).toFloat()
            _previewAspectRatio.value = videoRatio

            // Ensure preview surface buffer matches video 16:9 ratio exactly to prevent any vertical stretch
            previewSurfaceTexture?.let { texture ->
                val chars = getCharacteristics(lens.cameraId) ?: return@let
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                val matchingSize = previewSizes
                    .filter {
                        val r = max(it.width, it.height).toFloat() / min(it.width, it.height).toFloat()
                        kotlin.math.abs(r - videoRatio) < 0.05f
                    }
                    .filter { max(it.width, it.height) <= 1920 }
                    .maxByOrNull { it.width * it.height }
                    ?: previewSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                    ?: Size(videoRes.width, videoRes.height)

                texture.setDefaultBufferSize(matchingSize.width, matchingSize.height)
                previewSurface = Surface(texture)
            }

            val recorderSurface = mediaRecorder!!.surface
            val previewSurf = previewSurface ?: return

            val surfaces = listOf(previewSurf, recorderSurface)

            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurf)
                addTarget(recorderSurface)
                applyCommonSettings(this)
            }

            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            previewRequestBuilder?.let {
                                session.setRepeatingRequest(it.build(), captureCallback, backgroundHandler)
                            }
                            mediaRecorder?.start()
                            _isRecordingVideo.value = true
                            startVideoTimer()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start MediaRecorder recording", e)
                            val detail = getMediaCodecErrorDetail(e)
                            onError("Failed to start hardware recording: $detail")
                            stopVideoRecording()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Hardware video capture session configure failed")
                        _isRecordingVideo.value = false
                        try {
                            mediaRecorder?.reset()
                            mediaRecorder?.release()
                        } catch (ignored: Exception) {}
                        mediaRecorder = null
                        currentRecordingTempFile?.let { f ->
                            try { f.delete() } catch (ignored: Exception) {}
                            currentRecordingTempFile = null
                        }
                        currentVideoUri?.let { u ->
                            try { context.contentResolver.delete(u, null, null) } catch (ignored: Exception) {}
                            currentVideoUri = null
                        }
                        onError("Camera hardware failed to configure video capture session")
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video recording", e)
            val errorDetail = getMediaCodecErrorDetail(e)
            onError(errorDetail)
            _isRecordingVideo.value = false
            currentVideoUri?.let { uri ->
                try { context.contentResolver.delete(uri, null, null) } catch (ignored: Exception) {}
                currentVideoUri = null
            }
            currentRecordingTempFile?.let { f ->
                try { f.delete() } catch (ignored: Exception) {}
                currentRecordingTempFile = null
            }
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (ignored: Exception) {}
            mediaRecorder = null
            restartCamera()
        }
    }

    private fun startVideoTimer() {
        _videoDurationSeconds.value = 0
        videoTimerJob?.cancel()
        videoTimerJob = engineScope.launch {
            while (_isRecordingVideo.value) {
                delay(1000)
                _videoDurationSeconds.value += 1
            }
        }
    }

    /**
     * Stop Video Recording
     */
    fun stopVideoRecording() {
        if (!_isRecordingVideo.value && !isSoftwareCinemaRecording) return

        try {
            val isCinema = (currentMode == CameraMode.CINEMA)
            val fileName = currentVideoFileName ?: "VID_${System.currentTimeMillis()}.mp4"
            val mimeType = currentVideoMimeType ?: "video/mp4"
            currentVideoFileName = null
            currentVideoMimeType = null

            if (isSoftwareCinemaRecording) {
                isSoftwareCinemaRecording = false
                videoTimerJob?.cancel()
                _isRecordingVideo.value = false
                val recordedFile = cinemaSoftwareRecorder.stopRecording()
                currentRecordingTempFile = null
                val activeLens = _selectedLens.value
                val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

                engineScope.launch(Dispatchers.IO) {
                    try {
                        if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                            val savedUri = saveVideoToGallery(
                                tempFile = recordedFile,
                                fileName = fileName,
                                mimeType = mimeType,
                                isCinema = isCinema,
                                isFrontFacing = isFrontFacing
                            )
                            if (savedUri != null) {
                                _lastCapturedMedia.value = CapturedMedia(
                                    uri = savedUri,
                                    isVideo = true,
                                    timestamp = System.currentTimeMillis(),
                                    displayName = if (isCinema) "Cinema Video" else "Video",
                                    isFrontCamera = isFrontFacing
                                )
                                Log.i(TAG, "Cinema software video successfully saved to Gallery: size=${recordedFile.length()} bytes, uri=$savedUri")
                            } else {
                                Log.w(TAG, "Failed to save cinema software video to gallery")
                            }
                        } else {
                            Log.w(TAG, "Recorded software cinema file was missing or empty (length=${recordedFile?.length() ?: 0})")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save cinema recording to MediaStore", e)
                    } finally {
                        try { recordedFile?.delete() } catch (ignored: Exception) {}
                        updateStorageStats()
                    }
                }
                restartCamera()
                return
            }

            videoTimerJob?.cancel()
            _isRecordingVideo.value = false

            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder stop failed", e)
                }
                try { reset() } catch (ignored: Exception) {}
                try { release() } catch (ignored: Exception) {}
            }
            mediaRecorder = null

            videoRecordingFileDescriptor?.close()
            videoRecordingFileDescriptor = null

            val tempFile = currentRecordingTempFile
            currentRecordingTempFile = null

            val activeLens = _selectedLens.value
            val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

            engineScope.launch(Dispatchers.IO) {
                try {
                    if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                        val savedUri = saveVideoToGallery(
                            tempFile = tempFile,
                            fileName = fileName,
                            mimeType = mimeType,
                            isCinema = isCinema,
                            isFrontFacing = isFrontFacing
                        )
                        if (savedUri != null) {
                            _lastCapturedMedia.value = CapturedMedia(
                                uri = savedUri,
                                isVideo = true,
                                timestamp = System.currentTimeMillis(),
                                displayName = if (isCinema) "Cinema Video" else "Video",
                                isFrontCamera = isFrontFacing
                            )
                            Log.i(TAG, "Hardware recorded video successfully saved to Gallery: size=${tempFile.length()} bytes, uri=$savedUri")
                        } else {
                            Log.w(TAG, "Failed to save hardware recorded video to gallery")
                        }
                    } else {
                        Log.w(TAG, "Hardware temp recorded file was missing or empty (length=${tempFile?.length() ?: 0})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing recorded video", e)
                } finally {
                    try { tempFile?.delete() } catch (ignored: Exception) {}
                    updateStorageStats()
                }
            }

            restartCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video recording", e)
            restartCamera()
        }
    }

    /**
     * Reliable Gallery saving pipeline for recorded videos with automatic fallback.
     */
    private suspend fun saveVideoToGallery(
        tempFile: File,
        fileName: String,
        mimeType: String,
        isCinema: Boolean,
        isFrontFacing: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            Log.w(TAG, "saveVideoToGallery: tempFile is missing or empty")
            return@withContext null
        }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val dcimDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera"
                ).apply { if (!exists()) mkdirs() }
                val targetFile = File(dcimDir, fileName)
                put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        var targetUri: Uri? = null
        try {
            // Ensure physical DCIM/Camera directory exists
            try {
                val dcimDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera"
                )
                if (!dcimDir.exists()) dcimDir.mkdirs()
            } catch (ignored: Exception) {}

            targetUri = resolver.insert(collection, contentValues)
            if (targetUri != null) {
                resolver.openOutputStream(targetUri, "w")?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.SIZE, tempFile.length())
                    }
                    resolver.update(targetUri, updateValues, null, null)
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(tempFile.absolutePath),
                    arrayOf(mimeType)
                ) { _, scannedUri ->
                    Log.d(TAG, "Video scanned into MediaStore: $scannedUri")
                }
                return@withContext targetUri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary MediaStore insertion failed, falling back to direct DCIM/Camera write", e)
            if (targetUri != null) {
                try { resolver.delete(targetUri, null, null) } catch (ignored: Exception) {}
            }
        }

        // Direct DCIM/Camera fallback
        try {
            val dcimDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera"
            ).apply { if (!exists()) mkdirs() }
            val targetFile = File(dcimDir, fileName)
            tempFile.copyTo(targetFile, overwrite = true)

            var scannedUri: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                scannedUri = uri
                Log.d(TAG, "Fallback video scanned: $uri")
            }
            return@withContext scannedUri ?: Uri.fromFile(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback video save failed", e)
            return@withContext null
        }
    }

    /**
     * Extracts human-readable diagnostic error messages from MediaCodec / MediaRecorder exceptions.
     */
    private fun getMediaCodecErrorDetail(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is MediaCodec.CodecException) {
                return "MediaCodec error: ${cause.diagnosticInfo} (code=${cause.errorCode}, transient=${cause.isTransient})"
            }
            if (cause is java.io.IOException && cause.message?.contains("prepare failed") == true) {
                return "Hardware encoder prepare failed (unsupported codec, resolution, or profile level)"
            }
            cause = cause.cause
        }
        return e.localizedMessage ?: e.message ?: "Encoder initialization error"
    }

    private fun saveJpegToMediaStore(image: Image): Uri? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return saveJpegBytesToMediaStore(bytes)
    }

    private fun saveJpegBytesToMediaStore(bytes: ByteArray): Uri? {
        val activeLens = _selectedLens.value
        val isFrontFacing = activeLens?.facing == CameraCharacteristics.LENS_FACING_FRONT

        val finalBytes = if (isFrontFacing && saveSelfieAsPreviewed) {
            try {
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val exif = try {
                        android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                    } catch (e: Exception) { null }
                    val exifOrientation = exif?.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_UNDEFINED
                    ) ?: android.media.ExifInterface.ORIENTATION_UNDEFINED

                    val matrix = Matrix()
                    when (exifOrientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        else -> {
                            if (rawBitmap.width > rawBitmap.height) {
                                matrix.postRotate(270f)
                            }
                        }
                    }
                    // Mirror horizontally to save selfie exactly as previewed in viewfinder
                    matrix.postScale(-1f, 1f)

                    val mirroredBitmap = Bitmap.createBitmap(
                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                    )
                    if (mirroredBitmap != rawBitmap) {
                        rawBitmap.recycle()
                    }
                    val stream = java.io.ByteArrayOutputStream()
                    mirroredBitmap.compress(Bitmap.CompressFormat.JPEG, 98, stream)
                    mirroredBitmap.recycle()
                    stream.toByteArray()
                } else {
                    bytes
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to mirror selfie JPEG, falling back to original", e)
                bytes
            }
        } else {
            bytes
        }

        val photoFilter = selectedPhotoFilter
        val outputBytes = if (photoFilter != PhotoFilter.ORIGINAL && currentMode == CameraMode.PHOTO) {
            try {
                val matrix = photoFilter.toAndroidColorMatrix()
                val srcBmp = BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size)
                if (srcBmp != null && matrix != null) {
                    val filteredBmp = Bitmap.createBitmap(srcBmp.width, srcBmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(filteredBmp)
                    val paint = android.graphics.Paint().apply {
                        colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                    }
                    canvas.drawBitmap(srcBmp, 0f, 0f, paint)
                    srcBmp.recycle()
                    val stream = java.io.ByteArrayOutputStream()
                    filteredBmp.compress(Bitmap.CompressFormat.JPEG, 98, stream)
                    filteredBmp.recycle()
                    stream.toByteArray()
                } else {
                    srcBmp?.recycle()
                    finalBytes
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to apply photo filter to saved JPEG", e)
                finalBytes
            }
        } else {
            finalBytes
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(outputBytes)
            }

            if (isFrontFacing && saveSelfieAsPreviewed) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val outExif = android.media.ExifInterface(pfd.fileDescriptor)
                        outExif.setAttribute(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        outExif.saveAttributes()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write EXIF orientation on mirrored selfie", e)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            _lastCapturedMedia.value = CapturedMedia(
                uri = uri,
                isVideo = false,
                timestamp = System.currentTimeMillis(),
                displayName = fileName
            )
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save JPEG to media store", e)
            return null
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, orientationDegrees: Int): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_NIGHT_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            _lastCapturedMedia.value = CapturedMedia(
                uri = uri,
                isVideo = false,
                timestamp = System.currentTimeMillis(),
                displayName = fileName
            )
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save night bitmap", e)
            return null
        }
    }

    private fun saveRawToMediaStore(rawImage: Image, characteristics: CameraCharacteristics) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "RAW_$timeStamp.dng"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return

            val captureResult = lastCaptureResult ?: return
            val dngCreator = DngCreator(characteristics, captureResult)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                dngCreator.writeImage(out, rawImage)
            }
            dngCreator.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, completeValues, null, null)
            }
            Log.d(TAG, "Saved RAW DNG successfully to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RAW image", e)
        }
    }

    /**
     * Update storage statistics
     */
    fun updateStorageStats() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val bytesAvailable = stat.availableBytes
            val totalBytes = stat.totalBytes
            val freeGb = bytesAvailable.toFloat() / (1024 * 1024 * 1024)

            // Approximate: 5MB per JPEG photo, ~150MB per minute of 1080p video
            val estimatedPhotos = (bytesAvailable / (5L * 1024 * 1024)).toInt()
            val estimatedVideoMinutes = (bytesAvailable / (150L * 1024 * 1024)).toInt()

            _storageStats.value = StorageStats(
                freeBytes = bytesAvailable,
                totalBytes = totalBytes,
                freeGb = freeGb,
                estimatedPhotos = estimatedPhotos,
                estimatedVideoMinutes = estimatedVideoMinutes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage stats", e)
        }
    }

    fun restartCamera() {
        if (!_isCameraInitialized.value || previewSurfaceTexture == null) {
            Log.d(TAG, "Skipping restartCamera: not initialized or previewSurfaceTexture is null")
            return
        }
        backgroundHandler?.post {
            synchronized(cameraLifecycleLock) {
                if (isStartingCamera) {
                    restartPending = true
                    return@synchronized
                }
                closeCameraInternal()
                startCamera()
            }
        } ?: run {
            closeCamera()
            startCamera()
        }
    }

    fun applyViewfinderResolution(resolution: ViewfinderResolution) {
        if (viewfinderResolution == resolution) return
        viewfinderResolution = resolution
        restartCamera()
    }

    private fun closeCameraCaptureSession() {
        try {
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing capture session", e)
        }
    }

    private fun closeCameraInternal() {
        _isCameraReady.value = false
        gyroStabilizationEngine.stop()
        lastStabilizedCrop = null
        closeCameraCaptureSession()
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device", e)
        }
        try {
            previewSurface?.release()
            previewSurface = null
        } catch (ignored: Throwable) {}
        try {
            previewRequestBuilder = null
        } catch (ignored: Throwable) {}
        try {
            imageReaderJpeg?.close()
            imageReaderJpeg = null
            imageReaderRaw?.close()
            imageReaderRaw = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image readers", e)
        }
        isStartingCamera = false
    }

    fun closeCamera() {
        synchronized(cameraLifecycleLock) {
            closeCameraInternal()
        }
    }

    /**
     * Handles app moving to background (Settings, Home, switch app)
     */
    fun onAppBackgrounded() {
        Log.d(TAG, "onAppBackgrounded: closing camera cleanly")
        if (_isRecordingVideo.value) {
            try { stopVideoRecording() } catch (ignored: Throwable) {}
        }
        closeCamera()
    }

    /**
     * Handles app moving back to foreground
     */
    fun onAppForegrounded() {
        Log.d(TAG, "onAppForegrounded: re-initializing camera session")
        if (_isCameraInitialized.value && previewSurfaceTexture != null) {
            restartCamera()
        }
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }
}
