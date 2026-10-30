package com.futura.audio.engine

import android.util.Log

object AudioEngineBridge {
    private const val TAG = "AudioEngineBridge"

    fun startEngine(): Boolean {
        Log.d(TAG, "Starting audio engine")
        return true
    }

    fun stopEngine() {
        Log.d(TAG, "Stopping audio engine")
    }
}