package com.example.camera.esrgan

import android.net.Uri
import com.example.camera.model.PhotoMegapixelMode

/**
 * State of Real-ESRGAN AI Super-Resolution processing.
 */
data class RealEsrganState(
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val stageMessage: String = "",
    val activeMode: PhotoMegapixelMode? = null,
    val isGpuActive: Boolean = true,
    val targetResolutionText: String = "",
    val finalUri: Uri? = null,
    val error: String? = null
)
