package com.thejas.visionaiapp

import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(),TextToSpeech.OnInitListener {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var imageCapture: ImageCapture
    private var prompt = ""
    private var tempImageFile: File? = null
    private var imageBuffer: ByteBuffer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val speechRecognizerInterval = 10L
    private val activationKeyword = "hey vision"
    private val activationKeywordt = "vision"
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        previewView = findViewById(R.id.previewView)
        textToSpeech = TextToSpeech(this, this)

        // Check and request camera permissions
        if (hasCameraPermission()) {
            initializeCamera()
        } else {
            requestCameraPermission()
        }
        if (hasVoiceRecordingPermission()) {
            initializeSpeechRecognizer()
        } else {
            requestVoiceRecordingPermission()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        startSpeechRecognitionLoop()

    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set the language, if needed
            val langResult = textToSpeech.setLanguage(Locale.getDefault())

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TextToSpeech", "Language not supported")
            } else {
                // Text-to-speech engine is initialized successfully
                Log.d("TextToSpeech", "Initialization successful")
            }
        } else {
            Log.e("TextToSpeech", "Initialization failed")
        }
    }




    private fun startSpeechRecognitionLoop() {
        handler.postDelayed({
            initializeSpeechRecognizer()
            startSpeechRecognitionLoop()
        }, speechRecognizerInterval)
    }
    private fun hasVoiceRecordingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestVoiceRecordingPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_REQUEST_CODE
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    private fun initializeCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()
            if (hasCameraPermission()) { // Check again before initializing
                bindPreview(cameraProvider)
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder().build()

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()


        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            this as LifecycleOwner, cameraSelector, preview, imageCapture
        )
    }

    private fun initializeSpeechRecognizer() {
        var text = findViewById<TextView>(R.id.textforspeech)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        recognizerIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("SpeechRecognition", "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d("SpeechRecognition", "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                Log.d("SpeechRecognition", "onRmsChanged: $rmsdB")
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d("SpeechRecognition", "onBufferReceived")
            }

            override fun onEndOfSpeech() {
                Log.d("SpeechRecognition", "onEndOfSpeech")
            }

            override fun onError(error: Int) {
                Log.e("SpeechRecognition", "onError: $error")
            }

            override fun onResults(results: Bundle?) {
                Log.d("SpeechRecognition", "onResults")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (result in matches) {
                        prompt = result.toLowerCase(Locale.getDefault())
                        text.text = prompt
                        if (result.toLowerCase(Locale.getDefault()).contains(activationKeyword) || result.toLowerCase(Locale.getDefault()).contains(activationKeywordt) ) {
                            // Trigger your desired action here
                            Toast.makeText(
                                this@MainActivity,
                                "Activation Keyword Detected",
                                Toast.LENGTH_SHORT
                            ).show()
                            playActivationTone()
                            initializeSpeechRecognizer()
                            takePhoto()
                            // Add your additional actions here
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                Log.d("SpeechRecognition", "onPartialResults")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d("SpeechRecognition", "onEvent")
            }
        }

        speechRecognizer.setRecognitionListener(listener)
        startListening()
    }

    private fun startListening() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e("SpeechRecognition", "Error starting SpeechRecognizer: ${e.message}")
        }
    }

    private fun playActivationTone() {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
    }




    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped name and MediaStore entry.
        val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Get the saved file URI
                    val savedUri = output.savedUri ?: return // Handle null case

                    // Load the saved file into a Bitmap
                    val inputStream = contentResolver.openInputStream(savedUri)
                    val capturedImageBitmap = BitmapFactory.decodeStream(inputStream)

                    // Process the captured image
                    processCapturedImage(capturedImageBitmap)

                    // Close the input stream
                    inputStream?.close()
                }
            }
        )
    }




    private fun processCapturedImage(capturedImageBitmap: Bitmap) {
        // Now you can use 'capturedImageBitmap' for further processing or analysis
        // ...
        var text = findViewById<TextView>(R.id.text)
        var thejas = findViewById<TextView>(R.id.thejas)

        // Example: Pass the captured image to your generative model
        val generativeModel = GenerativeModel(
            modelName = "gemini-pro-vision",
            apiKey = "AIzaSyB864t68qZxueMuhY0YQV-z3CVFD1EMB40"
        )
        thejas.text = prompt

        // Use a coroutine to add a delay before generating content
        GlobalScope.launch(Dispatchers.Main) {
            delay(5000) // Add a delay of 2 seconds

            // Create inputContent after the delay
            thejas.text = prompt
            val inputContent = content {
                image(capturedImageBitmap)
                text(prompt)

            }

            // Code to execute after the delay
            try {
                val response = generativeModel.generateContent(inputContent)
                // Update your UI or perform actions based on the response

                text.text = response.text
                textToSpeech.speak(response.text, QUEUE_FLUSH, null, null)

            } catch (e: Exception) {
                // Handle exceptions here
                e.printStackTrace()
            }
        }
    }




    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 123
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 124
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null) // Remove any pending callbacks to avoid memory leaks
        speechRecognizer.destroy()
        cameraExecutor.shutdown()
    }
}
