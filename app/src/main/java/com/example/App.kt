package com.example

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        initYoutubeDL()
    }

    private fun initYoutubeDL() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@App)
                Log.d("App", "YoutubeDL initialized successfully")
            } catch (e: Exception) {
                Log.e("App", "Failed to initialize YoutubeDL: ${e.message}", e)
            }
        }
    }
}
