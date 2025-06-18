package com.k2fsa.sherpa.ncnn;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "sherpa-ncnn";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private final String[] permissions = {Manifest.permission.RECORD_AUDIO};
    private Button recordButton;
    private TextView textView;
    private IAsrService asrService;
    private boolean isServiceBound = false;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean isRecording = false;
    private boolean isRecognizing = false;

    private final IRecognitionCallback.Stub clientCallback = new IRecognitionCallback.Stub() {
        @Override
        public void onResult(String result) {
            runOnUiThread(() -> {
                textView.append("\nResult: " + result);
                Log.d(TAG, "Final Result: " + result);
            });
        }

        @Override
        public void onPartialResult(String partialResult) {
            runOnUiThread(() -> {
                textView.setText("Partial: " + partialResult);
                Log.d(TAG, "Partial Result: " + partialResult);
            });
        }

        @Override
        public void onError(String errorMessage) {
            runOnUiThread(() -> {
                textView.append("\nError: " + errorMessage);
                Log.e(TAG, "Error from service: " + errorMessage);
            });
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            asrService = IAsrService.Stub.asInterface(service);
            isServiceBound = true;
            Log.i(TAG, "Service connected");
            try {
                asrService.initModel(clientCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException on initModel", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            asrService = null;
            isServiceBound = false;
            Log.i(TAG, "Service disconnected");
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean permissionToRecordAccepted = (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) &&
                (grantResults[0] == PackageManager.PERMISSION_GRANTED);

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed");
            finish();
        }

        Log.i(TAG, "Audio record is permitted");
        startRecording();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(v -> onclick());

        textView = findViewById(R.id.my_text);
        textView.setMovementMethod(new ScrollingMovementMethod());

        Intent intent = new Intent();
        ComponentName componentName = new ComponentName(
                "com.k2fsa.sherpa.ncnn",
                "com.k2fsa.sherpa.ncnn.AsrService"
        );
        intent.setComponent(componentName);
        boolean bindResult = bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        Log.i(TAG, "Attempting to bind service, result: " + bindResult);
        if (!bindResult) {
            textView.setText("Failed to bind to service. Is the service APK installed and permission granted?");
        }

        startRecording();
    }

    private void startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }

        if (isRecording) {
            Log.d(TAG, "startRecording: skip");
            return;
        }

        isRecording = true;

        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
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
            audioRecord = null;
            return;
        }

        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            float interval = 0.1f; // 100 ms
            int bufferSize = (int) (interval * sampleRateInHz); // in samples
            short[] buffer = new short[bufferSize];

            //noinspection ConditionalBreakInInfiniteLoop
            while (isRecording) {
                if (audioRecord == null) break;
                if (isRecognizing) {
                    int ret = audioRecord.read(buffer, 0, buffer.length);
                    if (ret > 0) {
                        float[] samples = new float[ret];
                        for (int i = 0; i < ret; i++) {
                            samples[i] = buffer[i] / 32768.0f;
                        }
                        // 这里应该调用 processSamples 方法
                        if (asrService != null) {
                            try {
                                asrService.processSamples(samples);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to process samples", e);
                            }
                        }
                    }
                }
            }
        });
        recordingThread.start();
        Log.i(TAG, "Started recording");
    }

    private void onclick() {
        if (!isServiceBound || asrService == null) {
            Log.e(TAG, "Service not bound yet");
            return;
        }

        if (!isRecognizing) {
            isRecognizing = true;
            try {
                asrService.reset(true);
            } catch (RemoteException e) {
                Log.e(TAG, "onclick: ", e);
            }
            recordButton.setText(R.string.stop);
            textView.setText("");
        } else {
            isRecognizing = false;
            recordButton.setText(R.string.start);
        }
    }

    private void stopRecording() {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }
}