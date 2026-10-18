package dev.muve.muvi

import android.app.Application
import dev.muve.muvi.data.MuveRepo

class MuviApp : Application() {
    val repo: MuveRepo by lazy { MuveRepo(this) }
}
