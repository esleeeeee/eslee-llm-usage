package com.eslee.llmusage.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.eslee.llmusage.sync.SyncScheduler

class WidgetRefreshActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("accountId")?.let { SyncScheduler.refresh(this,it) }
        finish()
    }
}
