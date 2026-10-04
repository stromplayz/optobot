package com.optobot

import android.app.Application

/**
 * MainApplication — plain Application, no React Native.
 * Native modules (OptoVoice, OptoInferenceEngine, OptoReach) are
 * instantiated directly by MainActivity.
 */
class MainApplication : Application()
