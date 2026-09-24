package com.optobot

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.soloader.SoLoader
import com.optobot.native.OptoBotPackage

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this, false)
        if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
            load()
        }
        ReactNativeHost(this)
    }

    val reactHost: ReactHost
        get() = getDefaultReactHost(applicationContext, OptoReactPackageList())
}

class OptoReactPackageList : PackageList() {
    override fun getPackages(): List<ReactPackage> {
        val pkgs = super<Packages>().getPackages().toMutableList()
        pkgs.add(OptoBotPackage())
        return pkgs
    }
}
