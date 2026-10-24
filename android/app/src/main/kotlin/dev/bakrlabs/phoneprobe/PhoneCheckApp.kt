package dev.bakrlabs.phoneprobe

import android.app.Application

class PhoneCheckApp : Application() {
    val state by lazy { ProbeModel(this) }
}
