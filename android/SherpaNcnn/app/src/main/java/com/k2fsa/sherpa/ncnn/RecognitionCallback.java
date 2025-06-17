package com.k2fsa.sherpa.ncnn;

public interface RecognitionCallback {
    void onResult(String result);
    void onPartialResult(String partialResult);
    void onError(Exception error);
}