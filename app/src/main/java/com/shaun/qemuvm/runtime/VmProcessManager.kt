package com.shaun.qemuvm.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Non-blocking VM process abstraction layer for QEMU.
 *
 * Key goals:
 * - start() must NOT block
 * - stdout is streamed asynchronously
 * - process lifecycle is controllable
 */
class VmProcessManager {

    private var process: Process? = null
    private var logJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start VM process (non-blocking)
     */
    fun start(
        command: List<String>,
        onLine: (String) -> Unit = {}
    ): Process {

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)

        val p = pb.start()
        process = p

        // stream logs asynchronously
        logJob = scope.launch {
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line != null) {
                    onLine(line!!)
                }
            }
        }

        return p
    }

    /**
     * Await process exit (suspend)
     */
    suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        val p = process ?: return@withContext -1
        val code = p.waitFor()
        logJob?.cancel()
        return@withContext code
    }

    /**
     * Stop VM process
     */
    fun stop() {
        try {
            process?.destroy()
            process?.destroyForcibly()
        } finally {
            process = null
            logJob?.cancel()
            logJob = null
        }
    }

    /**
     * Check running state
     */
    fun isRunning(): Boolean {
        return process?.isAlive == true
    }
}
