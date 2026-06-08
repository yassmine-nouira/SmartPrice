package com.example.smartprice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var lastAnalysisTime = 0L
    private var classifier: ImageClassifier? = null
    private var db: PriceDatabase? = null
    private var cameraExecutor: ExecutorService? = null

    private lateinit var viewFinder: PreviewView
    private lateinit var productTitle: TextView
    private lateinit var priceDetails: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "✓ Layout loaded")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to load layout", e)
            showError("Layout error: ${e.message}")
            return
        }

        // Initialize views
        try {
            viewFinder = findViewById(R.id.viewFinder)
            productTitle = findViewById(R.id.productTitle)
            priceDetails = findViewById(R.id.priceDetails)
            Log.d(TAG, "✓ Views initialized")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to initialize views", e)
            showError("View initialization error")
            return
        }

        // Initialize executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize database
        try {
            db = PriceDatabase(this)
            Log.d(TAG, "✓ Database initialized")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Database initialization failed", e)
            productTitle.text = "Database Error"
            return
        }

        // Check and request permissions
        if (allPermissionsGranted()) {
            initializeApp()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun initializeApp() {
        productTitle.text = "Loading model..."
        priceDetails.text = "Please wait..."

        // Load model in background thread
        Thread {
            try {
                Log.d(TAG, "Loading classifier...")
                classifier = ImageClassifier(this)

                runOnUiThread {
                    productTitle.text = "Ready to scan!"
                    priceDetails.text = "Point camera at product"
                    priceDetails.setTextColor(getColor(android.R.color.holo_green_light))
                    startCamera()
                }

            } catch (e: Exception) {
                Log.e(TAG, "✗ Model loading failed", e)
                runOnUiThread {
                    productTitle.text = "Model Error"
                    priceDetails.text = e.message ?: "Unknown error"
                    priceDetails.setTextColor(getColor(android.R.color.holo_red_light))

                    Toast.makeText(
                        this,
                        "Please ensure mobilenet_products_mobile.pt is in assets/",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                // Image analysis
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor!!) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                Log.d(TAG, "✓ Camera started")

            } catch (e: Exception) {
                Log.e(TAG, "✗ Camera startup failed", e)
                runOnUiThread {
                    productTitle.text = "Camera Error"
                    priceDetails.text = e.message ?: "Camera failed"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        // Throttle: Only analyze every 1.2 seconds
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = currentTime

        // Check if classifier is ready
        val currentClassifier = classifier
        val currentDb = db

        if (currentClassifier == null || currentDb == null) {
            imageProxy.close()
            return
        }

        try {
            // Convert ImageProxy to Bitmap
            val bitmap = imageProxy.toBitmap()

            // Scale to model input size (224x224)
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

            // Classify
            val productName = currentClassifier.classify(scaledBitmap)

            // Get price info
            val cheapest = currentDb.getCheapest(productName)

            // Update UI
            runOnUiThread {
                when (productName) {
                    "Error" -> {
                        productTitle.text = "ERROR"
                        priceDetails.text = "Classification failed"
                        priceDetails.setTextColor(getColor(android.R.color.holo_red_light))
                    }
                    "Uncertain" -> {
                        productTitle.text = "UNCERTAIN"
                        priceDetails.text = "Move closer or adjust lighting"
                        priceDetails.setTextColor(getColor(android.R.color.holo_orange_light))
                    }
                    else -> {
                        productTitle.text = productName.uppercase()

                        if (cheapest.second > 0) {
                            priceDetails.text = "Best Price: ${cheapest.second} TND\nAt: ${cheapest.first}"
                            priceDetails.setTextColor(getColor(android.R.color.holo_green_light))
                        } else {
                            priceDetails.text = "Price not available"
                            priceDetails.setTextColor(getColor(android.R.color.holo_orange_light))
                        }
                    }
                }
            }

            // Clean up bitmaps
            bitmap.recycle()
            scaledBitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "✗ Image processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            width,
            height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, width, height),
            80,
            out
        )

        val imageBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Rotate if needed
        if (imageInfo.rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(imageInfo.rotationDegrees.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            bitmap = rotatedBitmap
        }

        return bitmap
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initializeApp()
            } else {
                productTitle.text = "Permission Denied"
                priceDetails.text = "Camera access required"
                Toast.makeText(
                    this,
                    "Camera permission is required for this app to work",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }

    companion object {
        private const val TAG = "SmartPrice"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private const val ANALYSIS_INTERVAL_MS = 1200L  // 1.2 seconds between analyses
        private const val MODEL_INPUT_SIZE = 224  // MobileNetV2 input size
    }
}