package com.k2fsa.sherpa.ncnn;

  import com.k2fsa.sherpa.ncnn.IRecognitionCallback;

  // ISherpaNcnnService.aidl
  interface IAsrService {
      void initModel(IRecognitionCallback callback);
      void reset(boolean recreate);
      void processSamples(in float[] samples);
  }