package com.k2fsa.sherpa.ncnn;

  // IRecognitionCallback.aidl
  oneway interface IRecognitionCallback {
      void onResult(String result);
      void onPartialResult(String partialResult);
      void onError(String errorMessage); // AIDL 不直接支持 Exception，传递错误信息字符串
  }
