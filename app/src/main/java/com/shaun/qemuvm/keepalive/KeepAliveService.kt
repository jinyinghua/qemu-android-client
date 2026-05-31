package com.shaun.qemuvm.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.shaun.qemuvm.MainActivity
import com.shaun.qemuvm.R
import com.shaun.qemuvm.app.QemuVmApplication
import com.shaun.qemuvm.qemu.QemuCommandBuilder
import com.shaun.qemuvm.util.NativeBinaryLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit

class KeepAliveService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var process: Process? = null
    @Volatile private var stoppingRequested = false
    @Volatile private var cloudInitServer: NoCloudServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stoppingRequested = true
            stopVm()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()

        lifecycleScope.launch(Dispatchers.IO) {
            startVm()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val stopIntent = Intent(this, KeepAliveService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val uiIntent = Intent(this, MainActivity::class.java)
        val uiPendingIntent = PendingIntent.getActivity(this, 0, uiIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_running))
            .setContentText("QEMU VM is running in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(uiPendingIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun startVm() {
        stoppingRequested = false
        val config = (application as QemuVmApplication).settingsRepository.snapshot().vmConfig

        if (config.diskImagePath.isBlank() && config.installMediaPath.isBlank()) {
            updateRuntimeStateSafely { it.copy(isRunning = false, lastError = "Disk image path or install media path missing") }
            stopSelf()
            return
        }
        if (config.firmwarePath.isBlank()) {
            updateRuntimeStateSafely { it.copy(isRunning = false, lastError = "Firmware path missing") }
            stopSelf()
            return
        }

        val inaccessible = mutableListOf<String>()
        collectFileIssue(config.diskImagePath, "disk image", inaccessible)
        collectFileIssue(config.installMediaPath, "install media", inaccessible)
        collectFileIssue(config.firmwarePath, "firmware", inaccessible)
        if (inaccessible.isNotEmpty()) {
            updateRuntimeStateSafely { it.copy(isRunning = false, lastError = "Cannot access:\n${inaccessible.joinToString("\n")}") }
            stopSelf()
            return
        }

        val qemuBin = NativeBinaryLocator.resolveExecutable(this, config.qemuBinaryName)
        val firmwareCodeFile = ensureFirmwareCodeImage(File(config.firmwarePath))
        val firmwareVarsFile = ensureFirmwareVarsImage()
        val qemuDataDir = ensureQemuDataDir()
        startCloudInitServer()
        val cloudSeedUrl = "http://10.0.2.2:${CLOUD_INIT_PORT}/"
        val args = QemuCommandBuilder().build(
            qemuBin,
            config,
            firmwareCodeFile.absolutePath,
            firmwareVarsFile.absolutePath,
            cloudSeedUrl,
            qemuDataDir.absolutePath
        )

        updateRuntimeStateSafely { it.copy(isRunning = true, lastCommandLine = args.joinToString(" "), lastError = "") }

        acquireWakeLock(config.keepScreenAwake)

        try {
            val pb = ProcessBuilder(args)
            NativeBinaryLocator.configureEnvironment(this, pb)
            pb.redirectErrorStream(true)
            process = pb.start()

            val currentProcess = process
            val output = withContext(Dispatchers.IO) {
                currentProcess?.inputStream?.bufferedReader()?.readText() ?: ""
            }
            val exitCode = currentProcess?.waitFor() ?: -1
            process = null

            val error = if (exitCode != 0) {
                val trimmed = output.trim()
                if (trimmed.isNotBlank()) {
                    trimmed.lines().takeLast(20).joinToString("\n")
                } else {
                    "QEMU exited with code $exitCode (no output)"
                }
            } else {
                ""
            }

            updateRuntimeStateSafely { it.copy(isRunning = false, lastExitCode = exitCode, lastError = error) }
        } catch (e: Exception) {
            process = null
            val msg = e.message ?: "Unknown error"
            if (stoppingRequested && msg.contains("interrupted", ignoreCase = true)) {
                updateRuntimeStateSafely { it.copy(isRunning = false, lastError = "") }
            } else {
                updateRuntimeStateSafely { it.copy(isRunning = false, lastError = msg) }
            }
        } finally {
            stopCloudInitServer()
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun ensureFirmwareCodeImage(sourceFirmware: File): File {
        val codeFile = File(filesDir, "qemu-efi-code.img")
        val targetSize = 64L * 1024L * 1024L
        val needsRefresh = !codeFile.exists() ||
            codeFile.length() != targetSize ||
            codeFile.lastModified() < sourceFirmware.lastModified()

        if (needsRefresh) {
            RandomAccessFile(codeFile, "rw").use { raf ->
                raf.setLength(targetSize)
                raf.seek(0)
                sourceFirmware.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                    }
                }
            }
        }
        return codeFile
    }

    private fun ensureFirmwareVarsImage(): File {
        val varsFile = File(filesDir, "qemu-efi-vars.img")
        if (!varsFile.exists() || varsFile.length() != 64L * 1024L * 1024L) {
            RandomAccessFile(varsFile, "rw").use { raf ->
                raf.setLength(64L * 1024L * 1024L)
            }
        }
        return varsFile
    }

    private fun ensureQemuDataDir(): File {
        val baseDir = File(filesDir, "qemu-data")
        val keymapsDir = File(baseDir, "keymaps")
        if (!keymapsDir.exists()) {
            keymapsDir.mkdirs()
        }
        val enUsKeymap = File(keymapsDir, "en-us")
        if (!enUsKeymap.exists()) {
            enUsKeymap.writeText("", Charsets.US_ASCII)
        }
        return baseDir
    }

    private fun startCloudInitServer() {
        if (cloudInitServer != null) return
        val server = NoCloudServer(CLOUD_INIT_PORT)
        server.start()
        cloudInitServer = server
    }

    private fun stopCloudInitServer() {
        cloudInitServer?.close()
        cloudInitServer = null
    }

    private suspend fun updateRuntimeStateSafely(transform: (com.shaun.qemuvm.data.VmRuntimeState) -> com.shaun.qemuvm.data.VmRuntimeState) {
        val repo = (application as QemuVmApplication).settingsRepository
        withContext(NonCancellable + Dispatchers.IO) {
            repo.updateRuntimeState(transform)
        }
    }

    private fun collectFileIssue(path: String, label: String, issues: MutableList<String>) {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return
        val file = File(trimmed)
        if (!file.exists()) {
            issues.add("$label: $trimmed")
        } else if (!file.canRead()) {
            issues.add("$label (no read permission): $trimmed")
        }
    }

    private fun stopVm() {
        val currentProcess = process
        var stopExitCode: Int? = null

        if (currentProcess != null) {
            runCatching {
                currentProcess.destroy()
                if (!currentProcess.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    currentProcess.destroyForcibly()
                    currentProcess.waitFor(1500, TimeUnit.MILLISECONDS)
                }
                if (!currentProcess.isAlive) {
                    stopExitCode = currentProcess.exitValue()
                }
            }
        }

        process = null
        stopCloudInitServer()
        releaseWakeLock()
        persistStoppedState(stopExitCode)
    }

    private fun persistStoppedState(exitCode: Int?) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                val repo = (application as QemuVmApplication).settingsRepository
                repo.updateRuntimeState {
                    it.copy(
                        isRunning = false,
                        lastExitCode = exitCode ?: it.lastExitCode,
                        lastError = if (stoppingRequested) "" else it.lastError
                    )
                }
            }
        }
    }

    private fun acquireWakeLock(keepScreenAwake: Boolean) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val levelAndFlags = if (keepScreenAwake) {
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
        } else {
            PowerManager.PARTIAL_WAKE_LOCK
        }
        wakeLock = pm.newWakeLock(levelAndFlags, "QemuVm::RuntimeWakeLock").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopVm()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.shaun.qemuvm.action.START"
        const val ACTION_STOP = "com.shaun.qemuvm.action.STOP"
        private const val CHANNEL_ID = "qemu_vm_channel"
        private const val NOTIFICATION_ID = 101
        private const val CLOUD_INIT_PORT = 8123
    }

    private class NoCloudServer(private val port: Int) {
        @Volatile private var running = true
        private var socket: ServerSocket? = null
        private var thread: Thread? = null
        private val userData = """
            #cloud-config
            password: qemu
            chpasswd: { expire: False }
            ssh_pwauth: True
        """.trimIndent() + "\n"
        private val metaData = """
            instance-id: qemu-android-client
            local-hostname: ubuntu-qemu
        """.trimIndent() + "\n"

        fun start() {
            thread = Thread {
                try {
                    socket = ServerSocket(port)
                    while (running) {
                        val client = try {
                            socket?.accept()
                        } catch (_: SocketException) {
                            null
                        } ?: break
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    // ignore
                } finally {
                    runCatching { socket?.close() }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun close() {
            running = false
            runCatching { socket?.close() }
            thread?.interrupt()
        }

        private fun handleClient(client: Socket) {
            client.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val output = BufferedOutputStream(sock.getOutputStream())
                val requestLine = readRequestLine(input)
                val path = requestLine?.split(' ')?.getOrNull(1) ?: "/"
                val body = when (path) {
                    "/user-data" -> userData
                    "/meta-data" -> metaData
                    "/network-config" -> ""
                    "/vendor-data" -> ""
                    else -> ""
                }
                writeResponse(output, body)
            }
        }

        private fun readRequestLine(input: BufferedInputStream): String? {
            val bytes = mutableListOf<Byte>()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code && prev == '\r'.code) break
                bytes.add(b.toByte())
                prev = b
                if (bytes.size > 4096) break
            }
            return bytes.toByteArray().toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
        }

        private fun writeResponse(output: BufferedOutputStream, body: String) {
            val data = body.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${data.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            output.write(headers)
            output.write(data)
            output.flush()
        }
    }
}
