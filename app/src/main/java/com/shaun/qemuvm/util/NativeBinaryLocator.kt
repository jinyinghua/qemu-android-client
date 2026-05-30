package com.shaun.qemuvm.util

import android.content.Context
import java.io.File
import java.lang.ProcessBuilder

object NativeBinaryLocator {
    fun resolveExecutable(context: Context, binaryName: String): File {
        return File(context.applicationInfo.nativeLibraryDir, binaryName)
    }

    fun configureEnvironment(context: Context, processBuilder: ProcessBuilder) {
        processBuilder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
    }
}
