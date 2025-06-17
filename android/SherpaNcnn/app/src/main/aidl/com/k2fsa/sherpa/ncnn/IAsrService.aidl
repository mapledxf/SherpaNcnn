package com.k2fsa.sherpa.ncnn;

  import com.k2fsa.sherpa.ncnn.IRecognitionCallback;

  // ISherpaNcnnService.aidl
  interface IAsrService {
      void initModel();
      boolean startRecording(IRecognitionCallback callback);
      void stopRecording();
      boolean isRecording();
  }

