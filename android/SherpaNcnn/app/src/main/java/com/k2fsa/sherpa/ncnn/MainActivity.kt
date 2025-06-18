//package com.k2fsa.sherpa.ncnn
//
//import android.Manifest
//import android.content.ComponentName
//import android.content.Intent
//import android.content.ServiceConnection
//import android.content.pm.PackageManager
//import android.media.AudioFormat
//import android.media.AudioRecord
//import android.media.MediaRecorder
//import android.os.Bundle
//import android.os.IBinder
//import android.os.RemoteException
//import android.text.method.ScrollingMovementMethod
//import android.util.Log
//import android.widget.Button
//import android.widget.TextView
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//
//
//private const val TAG = "sherpa-ncnn"
//private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
//
//class MainActivity : AppCompatActivity() {
//    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
//
//    private val SERVICE_ACTION: String =
//        "com.k2fsa.sherpa.ncnn.RECOGNITION_SERVICE_ACTION" // Service 的 Action
//    private val SERVICE_PACKAGE_NAME: String = "com.k2fsa.sherpa.ncnn" // Service APK 的包名
//
//    private lateinit var recordButton: Button
//    private lateinit var textView: TextView
//
//    private var sherpaNcnnService: IAsrService? = null
//    private var isServiceBound = false
//    private var audioRecord: AudioRecord? = null
//    private var recordingThread: Thread? = null
//    var isRecording: Boolean = false
//
//    private val clientCallback: IRecognitionCallback.Stub = object : IRecognitionCallback.Stub() {
//        @Throws(RemoteException::class)
//        override fun onResult(result: String) {
//            runOnUiThread {
//                textView.append("\nResult: $result")
//                Log.d(TAG, "Final Result: $result")
//            }
//        }
//
//        @Throws(RemoteException::class)
//        override fun onPartialResult(partialResult: String) {
//            runOnUiThread {
//                textView.text = "Partial: $partialResult"
//                Log.d(TAG, "Partial Result: $partialResult")
//            }
//        }
//
//        @Throws(RemoteException::class)
//        override fun onError(errorMessage: String) {
//            runOnUiThread {
//                textView.append("\nError: $errorMessage")
//                Log.e(TAG, "Error from service: $errorMessage")
//            }
//        }
//    }
//
//    private val serviceConnection: ServiceConnection = object : ServiceConnection {
//        override fun onServiceConnected(name: ComponentName, service: IBinder) {
//            sherpaNcnnService = IAsrService.Stub.asInterface(service)
//            isServiceBound = true
//            Log.i(TAG, "Service connected")
//            try {
//                // 连接成功后可以立即初始化模型
//                sherpaNcnnService!!.initModel(clientCallback)
//            } catch (e: RemoteException) {
//                Log.e(TAG, "RemoteException on initModel", e)
//            }
//        }
//
//        override fun onServiceDisconnected(name: ComponentName) {
//            sherpaNcnnService = null
//            isServiceBound = false
//            Log.i(TAG, "Service disconnected")
//        }
//    }
//
//    override fun onRequestPermissionsResult(
//        requestCode: Int,
//        permissions: Array<String>,
//        grantResults: IntArray
//    ) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
//            grantResults[0] == PackageManager.PERMISSION_GRANTED
//        } else {
//            false
//        }
//
//        if (!permissionToRecordAccepted) {
//            Log.e(TAG, "Audio record is disallowed")
//            finish()
//        }
//
//        Log.i(TAG, "Audio record is permitted")
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//
//        recordButton = findViewById(R.id.record_button)
//        recordButton.setOnClickListener { onclick() }
//
//        textView = findViewById(R.id.my_text)
//        textView.movementMethod = ScrollingMovementMethod()
//
//
//        if (!isServiceBound) {
//            val intent = Intent()
//            val componentName = ComponentName(
//                "com.k2fsa.sherpa.ncnn",  // Service 所在包名
//                "com.k2fsa.sherpa.ncnn.AsrService" // Service 的完整类名
//            )
//            intent.setComponent(componentName)
//            val bindResult: Boolean = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
//            Log.i(TAG, "Attempting to bind service, result: $bindResult")
//            if (!bindResult) {
//                textView.text = "Failed to bind to service. Is the service APK installed and permission granted?"
//            }
//        }
//
//
//        val sampleRateInHz = 16000
//        val channelConfig = AudioFormat.CHANNEL_IN_MONO
//        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
//        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
//
//        val audioSource = MediaRecorder.AudioSource.MIC
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.RECORD_AUDIO
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
//            return
//        }
//
//        audioRecord = AudioRecord(
//            audioSource,
//            sampleRateInHz,
//            channelConfig,
//            audioFormat,
//            numBytes * 2
//        )
//
//        if (audioRecord!!.state !== AudioRecord.STATE_INITIALIZED) {
//            Log.e(TAG, "Failed to initialize AudioRecord")
//            audioRecord = null
//            return
//        }
//
//        audioRecord!!.startRecording()
//
//        recordingThread = Thread {
//            val interval = 0.1f // 100 ms
//            val bufferSize = (interval * sampleRateInHz) as Int // in samples
//            val buffer = ShortArray(bufferSize)
//
//            while (true) {
//                if (audioRecord == null) break
//                val ret = audioRecord!!.read(buffer, 0, buffer.size)
//                if (ret > 0) {
//                    val samples = FloatArray(ret)
//                    for (i in 0 until ret) {
//                        samples[i] = buffer[i] / 32768.0f
//                    }
//
//                }
//            }
//        }
//        recordingThread!!.start()
//        Log.i(TAG, "Started recording")
//    }
//
//    private fun onclick() {
//        if (!isServiceBound || sherpaNcnnService == null) {
//            Log.e(TAG, "Service not bound yet")
//            return
//        }
//
//        if (!sherpaNcnnService!!.isRecording) {
//            // 确保在调用 startRecording 之前模型已经初始化
//            // sherpaNcnnService?.initModel() // 如果在 onServiceConnected 中没有初始化，可以在这里初始化
//
//            val success = sherpaNcnnService?.startRecording(clientCallback)
//
//            if (success == true) {
//                recordButton.setText(R.string.stop)
//                textView.text = ""
//            } else {
//                recordButton.setText(R.string.start)
//                textView.text = "Failed to start recording. Check logs."
//            }
//        } else {
//            sherpaNcnnService?.stopRecording()
//            recordButton.setText(R.string.start)
//        }
//    }
//
//    fun stopRecording() {
//        if (!isRecording) {
//            Log.w(TAG, "Recording is not in progress.")
//            return
//        }
//        isRecording = false
//        if (recordingThread != null) {
//            try {
//                recordingThread!!.join()
//            } catch (e: InterruptedException) {
//                Log.e(TAG, "Recording thread interrupted", e)
//            }
//            recordingThread = null
//        }
//        if (audioRecord != null) {
//            if (audioRecord!!.recordingState === AudioRecord.RECORDSTATE_RECORDING) {
//                audioRecord!!.stop()
//            }
//            audioRecord!!.release()
//            audioRecord = null
//        }
//        Log.i(TAG, "Stopped recording")
//    }
//    override fun onDestroy() {
//        super.onDestroy()
//        if (isServiceBound) {
//            unbindService(serviceConnection)
//            isServiceBound = false
//        }
//    }
//}
