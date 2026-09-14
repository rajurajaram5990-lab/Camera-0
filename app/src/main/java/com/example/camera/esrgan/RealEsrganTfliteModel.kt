package com.example.camera.esrgan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * High-performance TensorFlow Lite runner for official Real-ESRGAN models.
 *
 * Supports:
 * - [MODEL_V3]: 'real_esrgan_general_x4v3.tflite' (Float32, 1.21M parameters, SRVGGNetCompact)
 * - [MODEL_X4PLUS]: 'real_esrgan_x4plus.tflite' (w8a8, 16.7M parameters, RRDBNet)
 *
 * Input: 128x128 -> Output: 512x512 (4x Super-Resolution).
 * Reusable direct ByteBuffers eliminate GC overhead during large tile runs.
 */
class RealEsrganTfliteModel private constructor(
    val modelName: String,
    val modelFileSize: Long,
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    val isGpuAccelerated: Boolean,
    val isFloat32: Boolean,
    val inputDim: Int = 128,
    val outputDim: Int = 512
) : AutoCloseable {

    companion object {
        private const val TAG = "RealEsrganTfliteModel"

        const val MODEL_V3 = "real_esrgan_general_x4v3.tflite"
        const val MODEL_X4PLUS = "real_esrgan_x4plus.tflite"

        /**
         * Loads and validates the official Real-ESRGAN TFLite model from assets.
         * Throws [RealEsrganModelException] if model file is missing or corrupted.
         */
        fun create(context: Context, modelFileName: String, preferGpu: Boolean = true): RealEsrganTfliteModel {
            val assetPath = "models/$modelFileName"
            val afd = try {
                context.assets.openFd(assetPath)
            } catch (e: Exception) {
                throw RealEsrganModelException(
                    "Real-ESRGAN trained model weights '$modelFileName' not found in assets/models/. Verification failed.",
                    e
                )
            }

            val fileSize = afd.declaredLength
            if (fileSize <= 0) {
                afd.close()
                throw RealEsrganModelException("Real-ESRGAN model '$modelFileName' has invalid file size ($fileSize bytes).")
            }

            val modelBuffer: MappedByteBuffer = try {
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            } finally {
                afd.close()
            }

            var gpuDelegate: GpuDelegate? = null
            var useGpu = false

            if (preferGpu) {
                try {
                    gpuDelegate = GpuDelegate()
                    useGpu = true
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable on this device, falling back to multi-core CPU: ${t.message}")
                    gpuDelegate?.close()
                    gpuDelegate = null
                    useGpu = false
                }
            }

            val options = Interpreter.Options().apply {
                if (useGpu && gpuDelegate != null) {
                    addDelegate(gpuDelegate)
                } else {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                    setNumThreads(threads)
                }
            }

            val interpreter = try {
                Interpreter(modelBuffer, options)
            } catch (initErr: Throwable) {
                if (gpuDelegate != null) {
                    Log.w(TAG, "Interpreter creation failed with GPU delegate, retrying on CPU: ${initErr.message}")
                    gpuDelegate.close()
                    gpuDelegate = null
                    useGpu = false
                    val cpuOptions = Interpreter.Options().apply {
                        setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
                    }
                    try {
                        Interpreter(modelBuffer, cpuOptions)
                    } catch (cpuErr: Throwable) {
                        throw RealEsrganModelException("Failed to initialize Real-ESRGAN model on CPU: ${cpuErr.message}", cpuErr)
                    }
                } else {
                    throw RealEsrganModelException("Failed to initialize Real-ESRGAN model: ${initErr.message}", initErr)
                }
            }

            // Verify input and output tensors
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)

            val inShape = inputTensor.shape()
            val outShape = outputTensor.shape()
            val inType = inputTensor.dataType()
            val outType = outputTensor.dataType()

            Log.i(
                TAG,
                "Successfully loaded Real-ESRGAN model '$modelFileName' ($fileSize bytes): " +
                "Input: ${inShape.contentToString()} ($inType), Output: ${outShape.contentToString()} ($outType), " +
                "Backend: ${if (useGpu) "GPU Delegate" else "CPU multi-threaded"}"
            )

            val isFloat = (inType == DataType.FLOAT32)

            return RealEsrganTfliteModel(
                modelName = modelFileName,
                modelFileSize = fileSize,
                interpreter = interpreter,
                gpuDelegate = gpuDelegate,
                isGpuAccelerated = useGpu,
                isFloat32 = isFloat,
                inputDim = inShape.getOrNull(1) ?: 128,
                outputDim = outShape.getOrNull(1) ?: 512
            )
        }
    }

    // Direct byte buffers allocated once and reused across all tiles
    private val inputBuffer: ByteBuffer = if (isFloat32) {
        ByteBuffer.allocateDirect(1 * inputDim * inputDim * 3 * 4).order(ByteOrder.nativeOrder())
    } else {
        ByteBuffer.allocateDirect(1 * inputDim * inputDim * 3).order(ByteOrder.nativeOrder())
    }

    private val outputBuffer: ByteBuffer = if (isFloat32) {
        ByteBuffer.allocateDirect(1 * outputDim * outputDim * 3 * 4).order(ByteOrder.nativeOrder())
    } else {
        ByteBuffer.allocateDirect(1 * outputDim * outputDim * 3).order(ByteOrder.nativeOrder())
    }

    private val inPixels = IntArray(inputDim * inputDim)
    private val outPixels = IntArray(outputDim * outputDim)

    /**
     * Executes real neural super-resolution inference on a single 128x128 input tile.
     * Returns a high-frequency reconstructed 512x512 Bitmap.
     */
    @Synchronized
    fun upscaleTile(tile128: Bitmap): Bitmap {
        tile128.getPixels(inPixels, 0, inputDim, 0, 0, inputDim, inputDim)
        inputBuffer.rewind()

        if (isFloat32) {
            val floatBuf = inputBuffer.asFloatBuffer()
            for (pixel in inPixels) {
                floatBuf.put(((pixel shr 16) and 0xFF) / 255.0f)
                floatBuf.put(((pixel shr 8) and 0xFF) / 255.0f)
                floatBuf.put((pixel and 0xFF) / 255.0f)
            }
        } else {
            for (pixel in inPixels) {
                inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
                inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
                inputBuffer.put((pixel and 0xFF).toByte())
            }
        }

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        if (isFloat32) {
            val floatBuf = outputBuffer.asFloatBuffer()
            for (i in 0 until outputDim * outputDim) {
                val r = (floatBuf.get().coerceIn(0f, 1f) * 255.0f).roundToInt()
                val g = (floatBuf.get().coerceIn(0f, 1f) * 255.0f).roundToInt()
                val b = (floatBuf.get().coerceIn(0f, 1f) * 255.0f).roundToInt()
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            for (i in 0 until outputDim * outputDim) {
                val r = outputBuffer.get().toInt() and 0xFF
                val g = outputBuffer.get().toInt() and 0xFF
                val b = outputBuffer.get().toInt() and 0xFF
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val outBitmap = Bitmap.createBitmap(outputDim, outputDim, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, outputDim, 0, 0, outputDim, outputDim)
        return outBitmap
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing interpreter: ${e.message}")
        }
        try {
            gpuDelegate?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GPU delegate: ${e.message}")
        }
    }
}

class RealEsrganModelException(message: String, cause: Throwable? = null) : Exception(message, cause)
