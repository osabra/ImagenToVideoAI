package com.osabra.imagentovideoai

import android.net.Uri
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {
    private lateinit var imagePreview: ImageView
    private lateinit var promptInput: TextInputEditText
    private lateinit var durationGroup: RadioGroup
    private lateinit var generateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var videoView: VideoView
    private var selectedImageUri: Uri? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imagePreview.setImageURI(uri)
            statusText.text = "Imagen seleccionada. Escribe qué quieres que ocurra."
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
        if (selectedImageUri == null) {
            statusText.text = "Selecciona una imagen primero."
            return
        }

        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            statusText.text = "Describe el movimiento que quieres generar."
            return
        }

        val duration = when (durationGroup.checkedRadioButtonId) {
            R.id.duration10 -> 10
            R.id.duration15 -> 15
            else -> 5
        }

        // La interfaz está preparada para enviar imagen + prompt + duración
        // a un backend de Image-to-Video. La clave de API debe permanecer en servidor.
        setGeneratingState(true)
        statusText.text = "Preparando generación de ${duration}s..."

        window.decorView.postDelayed({
            setGeneratingState(false)
            statusText.text = "Interfaz lista. Falta conectar el servidor de IA."
        }, 900)
    }

    private fun setGeneratingState(generating: Boolean) {
        progressBar.visibility = if (generating) View.VISIBLE else View.GONE
        generateButton.isEnabled = !generating
    }
}
