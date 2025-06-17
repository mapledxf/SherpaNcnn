package com.k2fsa.sherpa.ncnn;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import androidx.core.app.ActivityCompat;

public class AsrService extends Service {
    private static final String TAG = "SherpaNcnnService";
    private SherpaNcnn model;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private RecognitionCallback recognitionCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final int sampleRateInHz = 16000;

    public boolean isRecording = false;
    private String lastText = "";
    private final IAsrService.Stub aidlBinder = new IAsrService.Stub() {
        @Override
        public void initModel() {
            AsrService.this.initModel();
        }

        @Override
        public boolean startRecording(IRecognitionCallback callback) throws RemoteException {
            // 将 AIDL 回调包装成我们内部的 RecognitionCallback
            RecognitionCallback internalCallback = new RecognitionCallback() {
                @Override
                public void onResult(String result) {
                    try {
                        if (callback != null) callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.e(TAG, "RemoteException in onResult", e);
                    }
                }

                @Override
                public void onPartialResult(String partialResult) {
                    try {
                        if (callback != null) callback.onPartialResult(partialResult);
                    } catch (RemoteException e) {
                        Log.e(TAG, "RemoteException in onPartialResult", e);
                    }
                }

                @Override
                public void onError(Exception error) {
                    try {
                        if (callback != null) callback.onError(error.getMessage());
                    } catch (RemoteException e) {
                        Log.e(TAG, "RemoteException in onError", e);
                    }
                }
            };
            return AsrService.this.startRecording(internalCallback);
        }

        @Override
        public void stopRecording() {
            AsrService.this.stopRecording();
        }

        @Override
        public boolean isRecording() {
            return AsrService.this.isRecording;
        }
    };


    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called with intent: " + intent);
        // 检查 action 是否匹配，虽然 AndroidManifest 中已经通过 intent-filter 过滤了
        if ("com.k2fsa.sherpa.ncnn.RECOGNITION_SERVICE_ACTION".equals(intent.getAction())) {
            return aidlBinder;
        }
        return null; // 或者返回一个默认的 Binder，或者抛出异常
    }

    public void initModel() {
        Log.i(TAG, "Start to initialize model");
        FeatureExtractorConfig featConfig = SherpaNcnnKt.getFeatureExtractorConfig(
                16000.0f,
                80
        );

        ModelConfig modelConfig = new ModelConfig(
                "model/encoder_jit_trace-pnnx.ncnn.param",
                "model/encoder_jit_trace-pnnx.ncnn.bin",
                "model/decoder_jit_trace-pnnx.ncnn.param",
                "model/decoder_jit_trace-pnnx.ncnn.bin",
                "model/joiner_jit_trace-pnnx.ncnn.param",
                "model/joiner_jit_trace-pnnx.ncnn.bin",
                "model/tokens.txt",
                1,
                true
                );

        DecoderConfig decoderConfig = SherpaNcnnKt.getDecoderConfig("greedy_search", 4);

        RecognizerConfig config = new RecognizerConfig(
                featConfig,
                modelConfig,
                decoderConfig,
                true,
                2.0f,
                0.8f,
                20.0f,
                "",
                1.5f
        );

        model = new SherpaNcnn(
                config,
                getApplication().getAssets()
        );
        Log.i(TAG, "Finished initializing model");
    }

    public boolean startRecording(RecognitionCallback callback) {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress.");
            return false;
        }
        this.recognitionCallback = callback;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (recognitionCallback != null) {
                recognitionCallback.onError(new SecurityException("RECORD_AUDIO permission not granted."));
            }
            Log.e(TAG, "RECORD_AUDIO permission not granted.");
            return false;
        }

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        Log.i(TAG, "Buffer size in milliseconds: " + (numBytes * 1000.0f / sampleRateInHz));

        int audioSource = MediaRecorder.AudioSource.MIC;
        audioRecord = new AudioRecord(
                audioSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                numBytes * 2
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Failed to initialize AudioRecord");
            if (recognitionCallback != null) {
                recognitionCallback.onError(new RuntimeException("Failed to initialize AudioRecord"));
            }
            audioRecord = null;
            return false;
        }

        audioRecord.startRecording();
        isRecording = true;
        lastText = "";

        recordingThread = new Thread(() -> {
            if (model != null) {
                model.reset(true);
            }
            processSamples();
        });
        recordingThread.start();
        Log.i(TAG, "Started recording");
        return true;
    }

    public void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Recording is not in progress.");
            return;
        }
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Recording thread interrupted", e);
                if (recognitionCallback != null) {
                    recognitionCallback.onError(e);
                }
            }
            recordingThread = null;
        }
        if (audioRecord != null) {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
            audioRecord.release();
            audioRecord = null;
        }
        Log.i(TAG, "Stopped recording");
    }

    private void processSamples() {
        Log.i(TAG, "Processing samples");

        float interval = 0.1f; // 100 ms
        int bufferSize = (int) (interval * sampleRateInHz); // in samples
        short[] buffer = new short[bufferSize];

        while (isRecording) {
            if (audioRecord == null) break;
            int ret = audioRecord.read(buffer, 0, buffer.length);
            if (ret > 0) {
                float[] samples = new float[ret];
                for (int i = 0; i < ret; i++) {
                    samples[i] = buffer[i] / 32768.0f;
                }
                if (model != null) {
                    model.acceptSamples(samples);
                    while (model.isReady()) {
                        model.decode();
                    }
                    boolean isEndpoint = model.isEndpoint();
                    String text = model.getText();
                    String textToDisplay = lastText;

                    if (!text.isEmpty()) {
                        if (lastText.isEmpty()) {
                            textToDisplay = text;
                        } else {
                            textToDisplay = lastText + "\n" + text;
                        }
                        final String currentPartialResult = textToDisplay;
                        if (recognitionCallback != null) {
                            mainHandler.post(() -> recognitionCallback.onPartialResult(currentPartialResult));
                        }
                    }

                    if (isEndpoint) {
                        model.reset(false);
                        if (!text.isEmpty()) {
                            lastText = textToDisplay;
                            final String currentResult = lastText;
                            if (recognitionCallback != null) {
                                mainHandler.post(() -> recognitionCallback.onResult(currentResult));
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
        if (model != null) {
            // 如果 SherpaNcnn 类有释放资源的方法，请在这里调用
            // model.release();
            model = null;
        }
        Log.i(TAG, "Service destroyed");
    }
}