package com.lyb.mysecretary

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.lyb.mysecretary.data.HistoryStore
import com.lyb.mysecretary.data.Settings
import com.lyb.mysecretary.data.WhitelistStore
import com.lyb.mysecretary.extract.GeminiNanoExtractor

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var whitelist: WhitelistStore
        private set
    val gemini = GeminiNanoExtractor()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        history = HistoryStore(this)
        whitelist = WhitelistStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "변환 진행 상황", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_ID = "processing"
    }
}
