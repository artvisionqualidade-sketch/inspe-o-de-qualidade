package br.com.qualiinsp.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** CameraX: captura tradicional e modo de inspeção visual contínua. */
class CameraXCaptureActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var statusLight: TextView
    private lateinit var detail: TextView
    private var cameraReady = false
    private var liveMode = false
    private var videoMode = false
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var actionButton: Button? = null
    private var lastAnalysisMs = 0L
    private var latestStatus = "ATTENTION"
    private var latestReason = "Aguardando análise de imagem"
    private var analysis: ImageAnalysis? = null
    private lateinit var liveModel: LiveModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        liveMode = intent.getBooleanExtra(EXTRA_LIVE_MODE, false)
        videoMode = intent.getBooleanExtra(EXTRA_VIDEO_MODE, false)
        if (liveMode) {
            liveModel = try { LiveModel.fromJson(intent.getStringExtra(EXTRA_LIVE_MODEL) ?: "{}") }
            catch (_: Exception) { LiveModel.empty() }
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val frame = FrameLayout(this)
        frame.addView(previewView)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 24)
            setBackgroundColor(0xDD101820.toInt())
        }
        statusLight = TextView(this).apply {
            text = if (liveMode) "🟡  PREPARANDO ANÁLISE" else if (videoMode) "GRAVAÇÃO MULTIÂNGULO" else "Câmera pronta"
            textSize = 19f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(12, 14, 12, 8)
        }
        detail = TextView(this).apply {
            text = if (liveMode) "Mova a câmera lentamente e enquadre toda a peça." else if (videoMode) "Grave 360° lentamente: frente, verso, laterais, topo, base e detalhes. Use boa iluminação e mantenha foco." else "Capture uma imagem nítida do produto."
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(12, 4, 12, 12)
        }
        controls.addView(statusLight, LinearLayout.LayoutParams(-1, -2))
        controls.addView(detail, LinearLayout.LayoutParams(-1, -2))
        actionButton = Button(this).apply {
            text = if (liveMode) "FINALIZAR INSPEÇÃO" else if (videoMode) "● INICIAR GRAVAÇÃO" else "CAPTURAR FOTO"
            isEnabled = false
            setOnClickListener { if (liveMode) finishLiveInspection() else if (videoMode) toggleVideoRecording(this) else takePhoto() }
        }
        controls.addView(actionButton, LinearLayout.LayoutParams(-1, -2))
        val cancelButton = Button(this).apply {
            text = "CANCELAR"
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }
        controls.addView(cancelButton, LinearLayout.LayoutParams(-1, -2))
        frame.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(frame)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95)
                    .setTargetRotation(previewView.display?.rotation ?: windowManager.defaultDisplay.rotation)
                    .build()
                provider.unbindAll()
                if (videoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    val videoUseCase = VideoCapture.withOutput(recorder)
                    videoCapture = videoUseCase
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoUseCase)
                } else if (liveMode) {
                    analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build().also { useCase ->
                            useCase.setAnalyzer(cameraExecutor) { image -> analyzeFrame(image) }
                        }
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } else {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                }
                cameraReady = true
                actionButton?.isEnabled = true
            } catch (e: Exception) {
                Toast.makeText(this, "Falha ao iniciar câmera: ${e.localizedMessage ?: "erro desconhecido"}", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED); finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleVideoRecording(button: Button) {
        if (!cameraReady) return
        if (recording != null) {
            button.isEnabled = false
            button.text = "FINALIZANDO VÍDEO..."
            recording?.stop()
            return
        }
        val capture = videoCapture ?: run { Toast.makeText(this, "Câmera de vídeo indisponível", Toast.LENGTH_LONG).show(); return }
        val file = File(cacheDir, "qualiinsp_video_${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file).build()
        try {
            recording = capture.output.prepareRecording(this, options).start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        button.text = "■ PARAR E ANALISAR VÍDEO"
                        statusLight.text = "🔴 GRAVANDO PRODUTO"
                        detail.text = "Gire a peça lentamente e mostre todos os lados. Evite movimentos bruscos e reflexos."
                    }
                    is VideoRecordEvent.Status -> {
                        val secs = event.recordingStats.recordedDurationNanos / 1_000_000_000
                        detail.text = "Gravando há ${secs}s • mostre frente, verso, laterais, topo, base e detalhes."
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        button.isEnabled = true
                        if (event.hasError()) {
                            statusLight.text = "🟡 FALHA NA GRAVAÇÃO"
                            detail.text = "Não foi possível finalizar o vídeo. Tente novamente."
                            file.delete()
                            button.text = "● TENTAR NOVAMENTE"
                            Toast.makeText(this, "Erro ao gravar vídeo: ${event.error}", Toast.LENGTH_LONG).show()
                        } else {
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_VIDEO_PATH, file.absolutePath))
                            finish()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            recording = null
            Toast.makeText(this, "Falha ao iniciar gravação: ${e.localizedMessage ?: "erro desconhecido"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (!liveMode || now - lastAnalysisMs < 450) return
            lastAnalysisMs = now
            val bitmap = rgbaToBitmap(image)
            val features = ML.features(bitmap)
            val result = liveModel.classify(features)
            runOnUiThread {
                latestStatus = result.first
                latestReason = result.second
                val (label, bg) = when (latestStatus) {
                    "OK" -> "🟢  CONFORME" to 0xFF197A45.toInt()
                    "BAD" -> "🔴  NÃO CONFORME" to 0xFFB3261E.toInt()
                    else -> "🟡  PRECISA DE AVALIAÇÃO" to 0xFF9A6700.toInt()
                }
                statusLight.text = label
                statusLight.setBackgroundColor(bg)
                detail.text = "$latestReason\nAnálise contínua ativa • resultado preliminar"
            }
            bitmap.recycle()
        } catch (_: Exception) {
            runOnUiThread { statusLight.text = "🟡  PRECISA DE AVALIAÇÃO"; detail.text = "Não foi possível analisar este quadro. Ajuste o foco e a iluminação." }
        } finally { image.close() }
    }

    private fun rgbaToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun finishLiveInspection() {
        if (!cameraReady) return
        analysis?.clearAnalyzer()
        setResult(Activity.RESULT_OK, Intent()
            .putExtra(EXTRA_LIVE_RESULT, true)
            .putExtra(EXTRA_LIVE_STATUS, latestStatus)
            .putExtra(EXTRA_LIVE_REASON, latestReason))
        finish()
    }

    private fun takePhoto() {
        if (!cameraReady) return
        val outputFile = File(cacheDir, "qualiinsp_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_IMAGE_PATH, outputFile.absolutePath)); finish()
            }
            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@CameraXCaptureActivity, "Falha ao capturar foto: ${exception.message ?: "erro desconhecido"}", Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onDestroy() {
        recording?.close()
        recording = null
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "qualiinsp.image_path"
        const val EXTRA_VIDEO_MODE = "qualiinsp.video_mode"
        const val EXTRA_VIDEO_PATH = "qualiinsp.video_path"
        const val EXTRA_LIVE_MODE = "qualiinsp.live_mode"
        const val EXTRA_LIVE_MODEL = "qualiinsp.live_model"
        const val EXTRA_LIVE_RESULT = "qualiinsp.live_result"
        const val EXTRA_LIVE_STATUS = "qualiinsp.live_status"
        const val EXTRA_LIVE_REASON = "qualiinsp.live_reason"
    }
}

private data class LiveDefect(val name: String, val threshold: Float, val critical: Boolean, val samples: List<List<Float>>)
private data class LiveModel(val references: List<List<Float>>, val defects: List<LiveDefect>) {
    fun classify(f: List<Float>): Pair<String, String> {
        if (references.isEmpty()) return "ATTENTION" to "Cadastre o padrão e exemplos CONFORME."
        val similarity = 1f - references.minOf { distance(f, it) }
        if (similarity < .45f) return "ATTENTION" to "Produto/padrão não identificado com segurança (${(similarity * 100).toInt()}%)."
        if (defects.isEmpty() || defects.none { it.samples.isNotEmpty() }) return "ATTENTION" to "Nenhum exemplo de defeito treinado; não é seguro aprovar automaticamente."
        val hits = defects.mapNotNull { d ->
            if (d.samples.isEmpty()) null else {
                val confidence = 1f - d.samples.minOf { distance(f, it) }
                if (confidence >= d.threshold) d.name to (confidence to d.critical) else null
            }
        }
        if (hits.isNotEmpty()) return "BAD" to "Possível defeito: ${hits.joinToString { "${it.first} (${(it.second.first * 100).toInt()}%)" }}."
        if (similarity < .60f) return "ATTENTION" to "Semelhança baixa com padrão (${(similarity * 100).toInt()}%); confira a peça."
        return "OK" to "Nenhum defeito configurado reconhecido neste quadro (${(similarity * 100).toInt()}% de similaridade)."
    }
    companion object {
        fun empty() = LiveModel(emptyList(), emptyList())
        fun fromJson(text: String): LiveModel {
            val o = JSONObject(text)
            fun readRows(a: JSONArray?): List<List<Float>> {
                if (a == null) return emptyList()
                return (0 until a.length()).map { i -> val row=a.getJSONArray(i); (0 until row.length()).map { row.getDouble(it).toFloat() } }
            }
            val refs = mutableListOf<List<Float>>()
            val standard = o.optJSONArray("standard")
            if (standard != null && standard.length() > 0) refs.add((0 until standard.length()).map { standard.getDouble(it).toFloat() })
            refs.addAll(readRows(o.optJSONArray("okSamples")))
            val defects = mutableListOf<LiveDefect>()
            val da = o.optJSONArray("defects") ?: JSONArray()
            for (i in 0 until da.length()) {
                val d=da.getJSONObject(i)
                defects.add(LiveDefect(d.optString("name"), d.optDouble("threshold", .35).toFloat(), d.optBoolean("critical"), readRows(d.optJSONArray("samples"))))
            }
            return LiveModel(refs, defects)
        }
    }
}

private fun distance(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 1f
    var sum=0f
    for (i in a.indices) { val delta=a[i]-b[i]; sum += delta*delta }
    return (sqrt(sum.toDouble()).toFloat()/3.5f).coerceIn(0f,1f)
}
