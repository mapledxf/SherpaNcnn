package com.k2fsa.sherpa.ncnn;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

public class AsrService extends Service {
    private static final String TAG = "AsrService";
    private SherpaNcnn model;
    private RecognitionCallback recognitionCallback;
    private boolean isInit = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String lastText = "";
    private final IAsrService.Stub aidlBinder = new IAsrService.Stub() {
        @Override
        public void initModel(IRecognitionCallback callback) {
            // 将 AIDL 回调包装成我们内部的 RecognitionCallback
            RecognitionCallback internalCallback = new RecognitionCallback() {
                @Override
                public void onResult(String result) {
                    Log.d(TAG, "onResult: " + result);
                    try {
                        if (callback != null) callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.e(TAG, "RemoteException in onResult", e);
                    }
                }

                @Override
                public void onPartialResult(String partialResult) {
                    Log.d(TAG, "onPartialResult: " + partialResult);
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
            AsrService.this.initModel(internalCallback);
        }

        @Override
        public void reset(boolean recreate) {
            AsrService.this.reset(recreate);
        }

        @Override
        public void processSamples(float[] samples) {
            AsrService.this.processSamples(samples);
        }
    };

    private void reset(boolean recreate) {
        if (model != null) {
            model.reset(recreate);
        }
        lastText = "";
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called with intent: " + intent);
        return aidlBinder;
    }

    public void initModel(RecognitionCallback callback) {
        this.recognitionCallback = callback;
        lastText = "";

        if (isInit) {
            Log.i(TAG, "Already init, skip");
            return;
        }

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
        isInit = true;
    }

    private void processSamples(float[] samples) {
        if (model != null) {
            model.acceptSamples(samples);
            while (model.isReady()) {
                model.decode();
            }
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

            if (model.isEndpoint()) {
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (model != null) {
            model = null;
        }
        Log.i(TAG, "Service destroyed");
    }
}