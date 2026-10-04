package com.optobot

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.soloader.SoLoader
import com.optobot.native.OptoBotPackage

class MainApplication : Application() {

    val reactNativeHost: ReactNativeHost = object : ReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> {
            val pkgs = PackageList(this).packages.toMutableList()
            pkgs.add(OptoBotPackage())
            return pkgs
        }

        override fun getJSMainModuleName(): String = "index"
        override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG
    }

    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this, false)
    }
}
