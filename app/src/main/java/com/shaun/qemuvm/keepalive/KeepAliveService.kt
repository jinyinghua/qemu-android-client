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
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

class KeepAliveService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var process: Process? = null
    @Volatile private var stoppingRequested = false

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
        val cloudSeedFile = ensureCloudInitSeedImage()
        val args = QemuCommandBuilder().build(
            qemuBin,
            config,
            firmwareCodeFile.absolutePath,
            firmwareVarsFile.absolutePath,
            cloudSeedFile.absolutePath,
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

    private fun ensureCloudInitSeedImage(): File {
        val seedFile = File(filesDir, "cloud-init-seed.img")
        val userData = (
            "#cloud-config\n" +
                "ssh_pwauth: true\n" +
                "users:\n" +
                "  - name: alpine\n" +
                "    lock_passwd: false\n" +
                "    plain_text_passwd: qemu\n" +
                "  - name: root\n" +
                "    lock_passwd: false\n" +
                "    plain_text_passwd: qemu\n" +
                "runcmd:\n" +
                "  - sed -i 's/^#\\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config\n" +
                "  - sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config\n" +
                "  - printf 'root:qemu\\nalpine:qemu\\n' | chpasswd\n" +
                "  - rc-service sshd restart\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        val metaData = (
            "instance-id: ${UUID.randomUUID()}\n" +
                "local-hostname: alpine-qemu\n"
            ).toByteArray(StandardCharsets.US_ASCII)

        writeSeedFatImage(seedFile, listOf(
            SeedFile("user-data", shortName = "USERDATA", shortExt = "", data = userData),
            SeedFile("meta-data", shortName = "METADATA", shortExt = "", data = metaData)
        ))
        return seedFile
    }

    private fun writeSeedFatImage(target: File, files: List<SeedFile>) {
        val bytesPerSector = 512
        val sectorsPerCluster = 1
        val reservedSectors = 1
        val fatCount = 2
        val rootEntries = 64
        val rootDirSectors = (rootEntries * 32 + bytesPerSector - 1) / bytesPerSector
        val totalSectors = 2880
        val mediaDescriptor = 0xF0
        val sectorsPerFat = 9
        val totalBytes = totalSectors * bytesPerSector
        val image = ByteArray(totalBytes)

        fun writeLe16(offset: Int, value: Int) {
            image[offset] = (value and 0xff).toByte()
            image[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }

        fun writeLe32(offset: Int, value: Int) {
            writeLe16(offset, value and 0xffff)
            writeLe16(offset + 2, (value ushr 16) and 0xffff)
        }

        image[0] = 0xeb.toByte()
        image[1] = 0x3c.toByte()
        image[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 3)
        writeLe16(11, bytesPerSector)
        image[13] = sectorsPerCluster.toByte()
        writeLe16(14, reservedSectors)
        image[16] = fatCount.toByte()
        writeLe16(17, rootEntries)
        writeLe16(19, totalSectors)
        image[21] = mediaDescriptor.toByte()
        writeLe16(22, sectorsPerFat)
        writeLe16(24, 18)
        writeLe16(26, 2)
        writeLe32(28, 0)
        writeLe32(32, 0)
        image[36] = 0x00
        image[37] = 0x00
        image[38] = 0x29.toByte()
        writeLe32(39, 0x51454d55)
        "CIDATA     ".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 43)
        "FAT12   ".toByteArray(StandardCharsets.US_ASCII).copyInto(image, 54)
        image[510] = 0x55.toByte()
        image[511] = 0xaa.toByte()

        val fatOffset = reservedSectors * bytesPerSector
        val rootOffset = fatOffset + fatCount * sectorsPerFat * bytesPerSector
        val dataOffset = rootOffset + rootDirSectors * bytesPerSector
        val clusterSize = sectorsPerCluster * bytesPerSector

        val fatEntries = IntArray(4084)
        fatEntries[0] = mediaDescriptor or 0xF00
        fatEntries[1] = 0xFFF

        var nextCluster = 2
        var rootEntryIndex = 0

        files.forEach { file ->
            val clusterCount = ((file.data.size + clusterSize - 1) / clusterSize).coerceAtLeast(1)
            val startCluster = nextCluster
            repeat(clusterCount) { index ->
                val cluster = nextCluster++
                fatEntries[cluster] = if (index == clusterCount - 1) 0xFFF else cluster + 1
                val writeOffset = dataOffset + (cluster - 2) * clusterSize
                val start = index * clusterSize
                val end = minOf(start + clusterSize, file.data.size)
                file.data.copyInto(image, writeOffset, start, end)
            }

            val shortName = buildShortName(file.shortName, file.shortExt)
            val checksum = lfnChecksum(shortName)
            val lfnEntries = buildLongFileNameEntries(file.longName, checksum)
            lfnEntries.forEach { entry ->
                val entryOffset = rootOffset + rootEntryIndex * 32
                rootEntryIndex++
                entry.copyInto(image, entryOffset)
            }

            val entryOffset = rootOffset + rootEntryIndex * 32
            rootEntryIndex++
            shortName.copyInto(image, entryOffset)
            image[entryOffset + 11] = 0x20
            writeLe16(entryOffset + 26, startCluster)
            writeLe32(entryOffset + 28, file.data.size)
        }

        repeat(fatCount) { fatIndex ->
            val targetOffset = fatOffset + fatIndex * sectorsPerFat * bytesPerSector
            var out = targetOffset
            var entry = 0
            while (entry + 1 < fatEntries.size && out + 2 < targetOffset + sectorsPerFat * bytesPerSector) {
                val a = fatEntries[entry] and 0xFFF
                val b = fatEntries[entry + 1] and 0xFFF
                image[out] = (a and 0xff).toByte()
                image[out + 1] = (((a ushr 8) and 0x0f) or ((b and 0x0f) shl 4)).toByte()
                image[out + 2] = ((b ushr 4) and 0xff).toByte()
                out += 3
                entry += 2
            }
        }

        target.writeBytes(image)
    }

    private fun buildShortName(name: String, ext: String): ByteArray {
        val base = name.uppercase()
            .filter { it.isLetterOrDigit() || it == '_' }
            .padEnd(8, ' ')
            .take(8)
        val suffix = ext.uppercase()
            .filter { it.isLetterOrDigit() || it == '_' }
            .padEnd(3, ' ')
            .take(3)
        return (base + suffix).toByteArray(StandardCharsets.US_ASCII)
    }

    private fun lfnChecksum(shortName: ByteArray): Byte {
        var sum = 0
        shortName.forEach { byte ->
            sum = (((sum and 1) shl 7) + (sum ushr 1) + (byte.toInt() and 0xff)) and 0xff
        }
        return sum.toByte()
    }

    private fun buildLongFileNameEntries(name: String, checksum: Byte): List<ByteArray> {
        val utf16 = name.toByteArray(StandardCharsets.UTF_16LE)
        val codeUnits = ArrayList<Int>()
        var index = 0
        while (index < utf16.size) {
            codeUnits += ((utf16[index + 1].toInt() and 0xff) shl 8) or (utf16[index].toInt() and 0xff)
            index += 2
        }
        codeUnits += 0x0000
        while (codeUnits.size % 13 != 0) codeUnits += 0xffff

        val chunks = codeUnits.chunked(13)
        return chunks.reversed().mapIndexed { idx, chunk ->
            val ordinal = chunks.size - idx
            ByteArray(32).also { entry ->
                entry[0] = (ordinal or if (idx == 0) 0x40 else 0x00).toByte()
                writeUtf16Chunk(entry, 1, chunk, 0, 5)
                entry[11] = 0x0f.toByte()
                entry[12] = 0x00
                entry[13] = checksum
                writeUtf16Chunk(entry, 14, chunk, 5, 6)
                entry[26] = 0x00
                entry[27] = 0x00
                writeUtf16Chunk(entry, 28, chunk, 11, 2)
            }
        }
    }

    private fun writeUtf16Chunk(target: ByteArray, offset: Int, units: List<Int>, start: Int, count: Int) {
        repeat(count) { index ->
            val value = units[start + index]
            val pos = offset + index * 2
            target[pos] = (value and 0xff).toByte()
            target[pos + 1] = ((value ushr 8) and 0xff).toByte()
        }
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
    }

    private data class SeedFile(
        val longName: String,
        val shortName: String,
        val shortExt: String,
        val data: ByteArray
    )
}
