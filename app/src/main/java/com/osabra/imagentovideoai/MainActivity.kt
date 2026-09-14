package com.osabra.imagentovideoai

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        private const val BACKEND_BASE = "https://imagentovideoai-backend.onrender.com"
    }

    private lateinit var imagePreview: ImageView
    private lateinit var promptInput: TextInputEditText
    private lateinit var durationGroup: RadioGroup
    private lateinit var generateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var videoView: VideoView
    private var selectedImageUri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imagePreview.setImageURI(uri)
            statusText.text = "Imagen seleccionada. Describe el movimiento."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        promptInput = findViewById(R.id.promptInput)
        durationGroup = findViewById(R.id.durationGroup)
        generateButton = findViewById(R.id.generateButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        videoView = findViewById(R.id.videoView)

        findViewById<Button>(R.id.selectImageButton).setOnClickListener {
            imagePicker.launch("image/*")
        }
        generateButton.setOnClickListener { generateVideo() }
    }

    private fun generateVideo() {
        val uri = selectedImageUri ?: run {
            statusText.text = "Selecciona una imagen primero."
            return
        }
        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            statusText.text = "Describe el movimiento que quieres generar."
            return
        }

        val duration = when (durationGroup.checkedRadioButtonId) {
            R.id.duration4 -> 4f
            else -> 5f
        }

        setGeneratingState(true)
        videoView.visibility = View.GONE
        statusText.text = "Enviando imagen al servidor…"

        Thread {
            try {
                val inputFile = copyUriToCache(uri)
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", inputFile.name, inputFile.asRequestBody(mime.toMediaTypeOrNull()))
                    .addFormDataPart("prompt", prompt.toRequestBody("text/plain".toMediaTypeOrNull()))
                    .addFormDataPart("duration", duration.toString().toRequestBody("text/plain".toMediaTypeOrNull()))
                    .build()

                val request = Request.Builder()
                    .url("$BACKEND_BASE/generate")
                    .post(multipart)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Servidor HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IllegalStateException("Respuesta vacía")
                    val jobId = JSONObject(body).getString("job_id")
                    runOnUiThread {
                        statusText.text = "Vídeo en cola. Preparando GPU…"
                        pollJob(jobId)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setGeneratingState(false)
                    statusText.text = "Error: ${e.message ?: "no se pudo iniciar la generación"}"
                }
            }
        }.start()
    }

    private fun pollJob(jobId: String) {
        Thread {
            try {
                val request = Request.Builder().url("$BACKEND_BASE/status/$jobId").get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Estado HTTP ${response.code}")
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val state = json.optString("status")
                    val message = json.optString("message", "Procesando…")

                    runOnUiThread {
                        when (state) {
                            "completed" -> downloadVideo(jobId)
                            "failed" -> {
                                setGeneratingState(false)
                                statusText.text = message
                            }
                            else -> {
                                statusText.text = message
                                handler.postDelayed({ pollJob(jobId) }, 5000)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setGeneratingState(false)
                    statusText.text = "Error comprobando el trabajo: ${e.message}"
                }
            }
        }.start()
    }

    private fun downloadVideo(jobId: String) {
        statusText.text = "Descargando vídeo…"
        Thread {
            try {
                val request = Request.Builder().url("$BACKEND_BASE/video/$jobId").get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Vídeo HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Vídeo vacío")
                    val output = File(cacheDir, "generated_${System.currentTimeMillis()}.mp4")
                    body.byteStream().use { input -> FileOutputStream(output).use { out -> input.copyTo(out) } }
                    runOnUiThread {
                        setGeneratingState(false)
                        statusText.text = "Vídeo generado correctamente."
                        videoView.visibility = View.VISIBLE
                        videoView.setVideoURI(Uri.fromFile(output))
                        videoView.setOnPreparedListener { it.isLooping = true }
                        videoView.start()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setGeneratingState(false)
                    statusText.text = "Error descargando el vídeo: ${e.message}"
                }
            }
        }.start()
    }

    private fun copyUriToCache(uri: Uri): File {
        val file = File(cacheDir, "input_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo leer la imagen" }
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun setGeneratingState(generating: Boolean) {
        progressBar.visibility = if (generating) View.VISIBLE else View.GONE
        generateButton.isEnabled = !generating
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
