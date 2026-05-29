package com.shaun.qemuvm.util

import android.content.Context
import java.io.File

object NativeBinaryLocator {
    fun resolveExecutable(context: Context, binaryName: String): File {
        return File(context.applicationInfo.nativeLibraryDir, binaryName)
    }
}
