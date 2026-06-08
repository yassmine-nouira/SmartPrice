package com.example.smartprice

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

class ImageClassifier(context: Context) {
    private var module: Module? = null

    // MUST match your Python training order exactly
    private val classes = arrayOf(
        "beurre",
        "farine",
        "lait"
    )

    init {
        try {
            Log.d(TAG, "Starting model initialization...")

            // Try loading from assets
            val modelPath = copyModelFromAssets(context, MODEL_FILE_NAME)

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                throw RuntimeException("Model file not found at: $modelPath")
            }

            Log.d(TAG, "Model file path: $modelPath")
            Log.d(TAG, "Model file size: ${modelFile.length() / (1024 * 1024)} MB")

            // Load PyTorch model
            module = Module.load(modelPath)
            Log.d(TAG, "✓ Model loaded successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to load model", e)
            throw RuntimeException(
                "Model loading failed. Please ensure '$MODEL_FILE_NAME' is in app/src/main/assets/\n" +
                        "Error: ${e.message}",
                e
            )
        }
    }

    fun classify(bitmap: Bitmap): String {
        val currentModule = module
        if (currentModule == null) {
            Log.e(TAG, "Module is null")
            return "Error: Model not loaded"
        }

        return try {
            // Convert bitmap to tensor with ImageNet normalization
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap,
                IMAGENET_MEAN,
                IMAGENET_STD
            )

            // Run inference
            val outputTensor = currentModule.forward(IValue.from(inputTensor)).toTensor()
            val scores = outputTensor.dataAsFloatArray

            // Apply softmax to get probabilities
            val probabilities = softmax(scores)

            // Log predictions
            if (ENABLE_DETAILED_LOGGING) {
                Log.d(TAG, "=== PREDICTION ===")
                for (i in classes.indices) {
                    Log.d(TAG, "  ${classes[i]}: ${"%.1f".format(probabilities[i] * 100)}%")
                }
            }

            // Find best prediction
            var maxProb = 0f
            var maxIndex = 0

            for (i in probabilities.indices) {
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxIndex = i
                }
            }

            val confidence = maxProb * 100
            val predictedClass = classes[maxIndex]

            Log.d(TAG, "Prediction: $predictedClass (${"%.1f".format(confidence)}%)")

            // Return prediction if confidence is above threshold
            if (confidence >= CONFIDENCE_THRESHOLD) {
                predictedClass
            } else {
                "Uncertain"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Classification error", e)
            "Error"
        }
    }

    private fun softmax(scores: FloatArray): FloatArray {
        // Find max for numerical stability
        var max = scores[0]
        for (score in scores) {
            if (score > max) max = score
        }

        // Compute exp(x - max)
        val expScores = FloatArray(scores.size)
        var sum = 0f
        for (i in scores.indices) {
            expScores[i] = Math.exp((scores[i] - max).toDouble()).toFloat()
            sum += expScores[i]
        }

        // Normalize
        for (i in expScores.indices) {
            expScores[i] /= sum
        }

        return expScores
    }

    private fun copyModelFromAssets(context: Context, fileName: String): String {
        val file = File(context.filesDir, fileName)

        // Use cached version if exists and not empty
        if (file.exists() && file.length() > 1_000_000) {  // > 1MB
            Log.d(TAG, "Using cached model")
            return file.absolutePath
        }

        Log.d(TAG, "Copying model from assets...")

        try {
            context.assets.open(fileName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }

                    outputStream.flush()
                    Log.d(TAG, "Model copied: ${totalBytes / (1024 * 1024)} MB")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets", e)
            throw RuntimeException("Cannot find model file in assets. Please add '$fileName' to app/src/main/assets/", e)
        }

        return file.absolutePath
    }

    companion object {
        private const val TAG = "ImageClassifier"
        private const val MODEL_FILE_NAME = "mobilenet_products_mobile.pt"

        // ImageNet normalization values
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        // Confidence threshold (40% minimum)
        private const val CONFIDENCE_THRESHOLD = 40f

        // Enable/disable detailed logging
        private const val ENABLE_DETAILED_LOGGING = true
    }
}