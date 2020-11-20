package com.vmb.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

typealias LumaListener = (luma: String) -> Unit

object Scanner {

    private const val TAG = "Scanner"
    const val Already_Code_Scanned = "Already Code Scanned"

    const val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private var imageCapture: ImageCapture? = null

    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private val scanCodes = ArrayList<String>()
    private var pauseScan: Boolean = false

    var mediaPlayer: MediaPlayer? = null
    private var mutePlayer: Boolean = false
    private lateinit var barCodeValue: String

    private var isCheckCodeExists: Boolean = true
    // default set log print false
    private var printLog: Boolean = false

    // camera resolution options
    lateinit var camera_resolution: Size
    val Low_Resolution = Size(176, 144)
    val Medium_Resolution = Size(352, 288)
    val High_Resolution = Size(640, 480)


    fun allPermissionsGranted(context: Context) = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                context, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun mediaPlayer(context: Context) {
        if (mediaPlayer == null)
            mediaPlayer = MediaPlayer.create(context, R.raw.beep)
        mediaPlayer?.setOnPreparedListener {
            log("Media Play Ready To Go")
        }
    }

    fun setBeepSound(afd: AssetFileDescriptor): Scanner {
        mediaPlayer?.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength())
        return this
    }

    fun startScanner(context: Context, viewFinder: PreviewView, scannerListener: ScannerListener): Scanner {

        // initial value set
        barCodeValue = ""

        if (allPermissionsGranted(context)) {
            // default set resolution to Low
            camera_resolution = Low_Resolution
            mediaPlayer(context)
            camera(context, context as AppCompatActivity, viewFinder, scannerListener)
        } else {
            log("Permissions not granted by the user.")
            ActivityCompat.requestPermissions(
                    context as Activity,
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
            )
        }

        return this
    }

    private fun camera(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            viewFinder: PreviewView,
            scannerListener: ScannerListener
    ) {

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            if (cameraProvider == null)
                cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                    }

            if (imageCapture == null)
                imageCapture = ImageCapture.Builder()
                        .setTargetResolution(camera_resolution)
                        .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                    .setImageQueueDepth(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                            if (luma != "0" && !isCheckCodeExists) {
                                scanSuccess(luma, scannerListener)
                            } else if (luma != "0" && !scanCodes.contains(luma)) {
                                scanSuccess(luma, scannerListener)
                            } else if (scanCodes.contains(luma)) {
                                loge("Scan Code : $luma $Already_Code_Scanned")
                                scannerListener.onFailed(Already_Code_Scanned)
                            }
                        })
                    }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalyzer
                )

            } catch (exc: Exception) {
                loge("Use case binding failed  $exc")
            }

        }, ContextCompat.getMainExecutor(context))

    }

    private fun scanSuccess(luma: String, scannerListener: ScannerListener) {
        log("Scan Code : $luma")

        scanCodes.add(luma)
        scannerListener.onSuccess(luma)

        if (!mutePlayer)
            mediaPlayer?.start()
    }

    fun pauseScan() {
        // Unbind use cases before rebinding
//        cameraProvider?.unbindAll()
        pauseScan = true
        log("Scanner is Paused")
    }

    fun resumeScan() {
        pauseScan = false
        log("Scanner is Resumed")
    }

    fun checkCodeExists(isCheck: Boolean): Scanner {
        isCheckCodeExists = isCheck
        log("Scanner : Check code already scanned is $isCheck")
        return this
    }

    fun muteBeepSound(mute: Boolean): Scanner {
        mutePlayer = mute
        log("Scanner sound is muted")
        return this
    }

    fun setResolution(resolution: Size): Scanner {
        camera_resolution = resolution
        log("Resolution set to $resolution")
        return this
    }

    fun logPrint(printLog: Boolean): Scanner {
        this.printLog = printLog
        return this
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L

        private val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

        //    specify the formats to recognize:
        val scanner = BarcodeScanning.getClient(/*options*/)

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        @SuppressLint("UnsafeExperimentalUsageError")
        @RequiresApi(Build.VERSION_CODES.KITKAT)
        override fun analyze(imageProxy: ImageProxy) {

            val currentTimestamp = System.currentTimeMillis()
            // Calculate the average luma no more often than every second
            if (currentTimestamp - lastAnalyzedTimestamp >= 500) {

                // pause or resume scanner
                if (pauseScan) return

                imageProxy.image.let { _mediaImage ->

                    InputImage.fromMediaImage(
                            _mediaImage!!,
                            imageProxy.imageInfo.rotationDegrees
                    ).let { _image ->

                        // Pass image to an ML Kit Vision API
                        scanner.process(_image)
                                .addOnSuccessListener { barcodes ->
                                    // Task completed successfully
                                    // ...

                                    // pause or resume scanner
                                    if (pauseScan) return@addOnSuccessListener

                                    for (barcode in barcodes) {
                                        val bounds = barcode.boundingBox
                                        val corners = barcode.cornerPoints

                                        val rawValue = barcode.rawValue

                                        log("Scan Codes : Bounds = $bounds, Corners = $corners, RawValue = $rawValue ")

                                        rawValue?.let {
                                            if (barCodeValue != it) {
                                                listener(it)
                                            }
                                            // store value
                                            barCodeValue = it

                                        }

                                        // additional data retrieve
                                        /*  val valueType = barcode.valueType
                                          // See API reference for complete list of supported types
                                          when (valueType) {
                                              Barcode.TYPE_WIFI -> {
                                                  val ssid = barcode.wifi!!.ssid
                                                  val password = barcode.wifi!!.password
                                                  val type = barcode.wifi!!.encryptionType
                                              }
                                              Barcode.TYPE_URL -> {
                                                  val title = barcode.url!!.title
                                                  val url = barcode.url!!.url
                                              }
                                          }*/
                                    }

                                }
                                .addOnFailureListener {
                                    // Task failed with an exception
//                                    it.printStackTrace()

                                    // clear data
//                                barCodeValue = ""
                                    log("analyze: Older value removed")
                                }
                                .addOnCompleteListener {
                                    try {
                                        if (it?.result?.isEmpty()!!) {
                                            barCodeValue = ""       // clear data
                                            log("analyze: Older value Completed : ${it?.result}")
                                        }
                                    } catch (e: Exception) {
                                        loge(e.message!!)
                                    }

                                }

                    }

                }

                val buffer = imageProxy.planes[0].buffer
                val data = buffer.toByteArray()
                val pixels = data.map { it.toInt() and 0xFF }
                val luma = pixels.average()

                listener("0")


                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp
            }

            imageProxy.close()
        }

    }

    fun destroyScanner() {
        if (this::cameraExecutor.isInitialized)
            cameraExecutor.shutdown()
        log("onDestroy: Scanner")
    }


    private fun log(d: String) {
        if (printLog)
            Log.d(TAG, d)
    }

    private fun loge(e: String) {
        if (printLog)
            Log.e(TAG, e)
    }
}