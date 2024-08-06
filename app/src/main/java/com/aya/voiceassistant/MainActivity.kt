package com.aya.voiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aya.voiceassistant.databinding.ActivityMainBinding
import com.aya.voiceassistant.whisper.asr.IRecorderListener
import com.aya.voiceassistant.whisper.asr.IWhisperListener
import com.aya.voiceassistant.whisper.asr.Recorder
import com.aya.voiceassistant.whisper.asr.Whisper
import com.aya.voiceassistant.whisper.utils.WaveUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream


class MainActivity : AppCompatActivity(), TextEmbedderHelper.EmbedderListener, OnClickListener {

    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var textEmbedderHelper: TextEmbedderHelper
    private lateinit var whisper: Whisper
    private lateinit var recorder: Recorder
    private val handler = Handler(Looper.getMainLooper())

    private var recordingFlag = false
    private var parsing = false
    private var embedParsing = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        textEmbedderHelper = TextEmbedderHelper(this, listener = this)

        initWhisper()
        initRecorder()
        resetEmbedder()
        initUI()

    }

    private fun initWhisper(useMultilingual: Boolean = true) {

        // Call the method to copy specific file types from assets to data folder
        val extensionsToCopy = arrayOf("wav", "bin", "tflite")
        copyAssetsWithExtensionsToDataFolder(this, extensionsToCopy)

        whisper = Whisper(this)

        val modelPath: String?
        val vocabPath: String?
        if (useMultilingual) {
            modelPath = getFilePath("whisper-tiny.tflite")
            vocabPath = getFilePath("filters_vocab_multilingual.bin")
        } else {
            modelPath = getFilePath("whisper-en.tflite")
            vocabPath = getFilePath("filters_vocab_en.bin")
        }
        whisper.loadModel(modelPath, vocabPath, useMultilingual)
        whisper.setListener(object : IWhisperListener {
            override fun onUpdateReceived(message: String) {
                Log.d(TAG, "Update is received, Message: $message")
                handler.post { binding.tvStatus.text = message }

                if (message == Whisper.MSG_PROCESSING) {
                    Log.d(TAG, "processing...!")
                    parsing = true
                    handler.post { binding.tvStatus.text = message }
                } else if (message == Whisper.MSG_FILE_NOT_FOUND) {
                    // write code as per need to handled this error
                    Log.d(TAG, "File not found error...!")
                    parsing = false
                    handler.post { binding.tvStatus.text = message }
                } else if (message == Whisper.MSG_PROCESSING_DONE) {
                    parsing = false
                    Log.d(TAG, "Processing done...!")
                    handler.post { binding.tvStatus.text = message }
                } else {
                    parsing = false
                    Log.e(TAG, "Result: un handle")
                }
            }

            override fun onResultReceived(result: String) {
                Log.d(TAG, "Result: $result")
                parsing = false
                handler.post { binding.etResult.setText(result) }
            }
        })
    }

    private fun initRecorder() {
        recorder = Recorder(this)
        recorder.setListener(object : IRecorderListener {
            override fun onUpdateReceived(message: String) {
                Log.d(TAG, "Update is received, Message: $message")
                if (message == Recorder.MSG_RECORDING || message == Recorder.MSG_RECORDING_DONE) {
                    handler.post { binding.tvStatus.text = message }
                } else {
                    // fixme: useless
                    handler.post { binding.tvStatus.text = message }
                }
            }

            override fun onDataReceived(samples: FloatArray?) {
                //mWhisper.writeBuffer(samples);
                Log.d(TAG, "onDataReceived: ")
            }
        })
        checkRecordPermission();
    }

    private fun initUI() {
        binding.btnRecording.setOnClickListener(this)
        binding.btnWhisper.setOnClickListener(this)
        binding.btnExecute.setOnClickListener(this)
    }


    private fun startRecording() {
        checkRecordPermission()
        recordingFlag = true
        val waveFilePath = getFilePath(WaveUtil.RECORDING_FILE)
        recorder.setFilePath(waveFilePath)
        recorder.start()
        handler.post { binding.btnRecording.setImageResource(R.drawable.recording_stop_icon) }
    }

    private fun stopRecording() {
        recorder.stop()
        recordingFlag = false
        handler.post { binding.btnRecording.setImageResource(R.drawable.recording_icon) }
    }

    private fun startTranscription(waveFilePath: String) {
        whisper.setFilePath(waveFilePath)
        whisper.setAction(Whisper.ACTION_TRANSCRIBE)
        whisper.start()

    }

    private fun stopTranscription() {
        whisper.stop()
    }

    private fun getFilePath(assetName: String): String? {
        // fixme:
        val outfile = File(filesDir, assetName)
        if (!outfile.exists()) {
            Log.d(TAG, "File not found - " + outfile.absolutePath)
        }
        Log.d(TAG, "Returned asset path: " + outfile.absolutePath)
        return outfile.absolutePath
    }

    private fun resetEmbedder() {
        textEmbedderHelper.clearTextEmbedder()
        textEmbedderHelper.setupTextEmbedder()
    }

    private fun compareOrder(text: String) {
        embedParsing = true
        binding.tvStatus.text = "analyzing...!"
        val orders = arrayOf("open youtube")
        Log.d(TAG, "compareOrder: text: $text")
        var result: TextEmbedderHelper.ResultBundle? = null
        var result_order: String? = null
        for (order in orders) {
            textEmbedderHelper.compare(text, order)?.let { resultBundle ->
                Log.d(TAG, "compareOrder: order: $order, similarity: ${resultBundle.similarity}")
                if (result == null || resultBundle.similarity > result!!.similarity) {
                    result = resultBundle
                    result_order = order
                }
            }
        }

        updateEmbedResult(result!!, result_order)


    }


    private fun updateEmbedResult(resultBundle: TextEmbedderHelper.ResultBundle, order: String?) {
        handler.post { binding.tvStatus.text = "analyzing done...!" }
        embedParsing = false
//        Log.d(TAG, String.format("updateEmbedResult Similarity: %.2f", resultBundle.similarity))
        Log.d(TAG, "updateEmbedResult order: $order, Similarity: ${resultBundle.similarity}}")

        if (resultBundle.similarity > 0.8 && order.equals("open youtube")) {
            val launchIntent =
                packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "YouTube app is not installed", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun checkRecordPermission() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted")
        } else {
            Log.d(TAG, "Requesting record permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) Log.d(
            TAG,
            "Record permission is granted"
        ) else Log.d(TAG, "Record permission is not granted")
    }

    // todo: don't use this
    private fun copyAssetsWithExtensionsToDataFolder(context: Context, extensions: Array<String>) {
        val assetManager = context.assets
        try {
            // Specify the destination directory in the app's data folder
            val destFolder = context.filesDir.absolutePath
            for (extension in extensions) {
                // List all files in the assets folder with the specified extension
                val assetFiles = assetManager.list("")
                for (assetFileName in assetFiles!!) {
                    if (assetFileName.endsWith(".$extension")) {
                        val outFile = File(destFolder, assetFileName)
                        if (outFile.exists()) continue
                        val inputStream = assetManager.open(assetFileName)
                        val outputStream: OutputStream = FileOutputStream(outFile)

                        // Copy the file from assets to the data folder
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                        inputStream.close()
                        outputStream.flush()
                        outputStream.close()
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
    }

    override fun onClick(p0: View?) {
        when (p0?.id) {
            binding.btnRecording.id -> {
                if (recordingFlag) stopRecording() else startRecording()
            }

            binding.btnWhisper.id -> {
                if (recordingFlag) {
                    Log.e(TAG, "onClick: still recording...")
                    return
                } else if (parsing) {
                    Log.e(TAG, "onClick: still parsing...")
                    return
                }
                startTranscription(getFilePath(WaveUtil.RECORDING_FILE)!!)
//                startTranscription(getFilePath("english_test.wav")!!)     // for test

            }

            binding.btnExecute.id -> {
                if (recordingFlag || parsing || embedParsing) {
                    Log.e(TAG, "onClick: still process other task...")
                    return
                }
                compareOrder(binding.etResult.text.toString())
            }
        }
    }

}