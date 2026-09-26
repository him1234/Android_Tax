package com.example.taxledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.taxledger.data.InvoiceFields
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

class InvoiceScanActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ocr = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val barcodes = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private var bestText = ""
    private var qrNumber = ""
    @Volatile private var busy = false
    private var lastFrame = 0L

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "需要相机权限才能实时识别；可返回使用文件导入。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        status = TextView(this).apply { text = "将发票放入画面，保持文字清晰"; textSize = 17f; setPadding(24, 16, 24, 16) }
        confirm = Button(this).apply {
            text = "使用识别结果"; isEnabled = false
            setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, bestText).putExtra(EXTRA_QR, qrNumber))
                finish()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(confirm, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(layout)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { frame ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (busy || now - lastFrame < 500L) { frame.close(); return@setAnalyzer }
                    val media = frame.image
                    if (media == null) { frame.close(); return@setAnalyzer }
                    busy = true
                    lastFrame = now
                    val image = InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)
                    val textTask = ocr.process(image)
                    val qrTask = barcodes.process(image)
                    textTask.addOnSuccessListener { result ->
                        val candidate = result.text
                        if (candidate.isNotBlank()) bestText = (bestText + "\n" + candidate).takeLast(16000)
                        showFields()
                    }
                    qrTask.addOnSuccessListener { results ->
                        results.firstNotNullOfOrNull { qr ->
                            Regex("(?<!\\d)\\d{20}(?!\\d)").find(qr.rawValue.orEmpty())?.value
                        }?.let { qrNumber = it }
                        showFields()
                    }
                    Tasks.whenAllComplete(textTask, qrTask).addOnCompleteListener {
                        frame.close()
                        busy = false
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            }.onFailure { status.text = "相机启动失败：${it.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showFields() {
        val fields = InvoiceFields.fromText(bestText, qrNumber)
        status.text = "号码：${fields.invoiceNumber.ifBlank { "待识别" }}\n金额：${fields.grossAmount ?: "待识别"}\n日期：${fields.issuedOn ?: "待识别"}\n请核对后保存。"
        confirm.isEnabled = fields.invoiceNumber.isNotBlank() || fields.grossAmount != null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        ocr.close()
        barcodes.close()
    }

    companion object {
        const val EXTRA_TEXT = "invoice_ocr_text"
        const val EXTRA_QR = "invoice_qr_number"
    }
}
