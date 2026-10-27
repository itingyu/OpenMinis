package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-ubuntu-rootfs] One-time install of the userland tools into a freshly
 * extracted Ubuntu rootfs.
 *
 * Runs through [ShellExecutor] (so it goes through the same proot + bind mounts
 * a chat command uses) and leaves a marker file behind, making it safe to call
 * on every boot. A failure is only logged: the sandbox still works, the user can
 * retry with `apt-get install` in the terminal.
 */
internal object UbuntuRootfsBootstrap {

    private const val TAG = "UbuntuRootfs"
    private const val MARKER = ".minis-base-ok"
    private const val BUDGET_MS = 15 * 60 * 1000L

    private val mutex = Mutex()

    /** Packages every fresh Ubuntu sandbox gets, roughly Alpine's busybox set. */
    private val BASE_PACKAGES = listOf(
        "bash", "ca-certificates", "curl", "wget", "git", "ripgrep", "less",
        "procps", "coreutils", "findutils", "tar", "xz-utils", "unzip", "zip",
        "python3", "python3-pip", "python3-venv", "jq", "nano", "openssh-client",
    )

    private fun marker(rootfsDir: File) = File(rootfsDir, MARKER)

    suspend fun ensureBaseInstalled(context: Context): Boolean = mutex.withLock {
        val app = context.applicationContext
        val rootfs = RootfsManager.getInstance(app).rootfsDir
        if (marker(rootfs).exists()) return@withLock true
        if (!PRootKernel.isBooted) return@withLock false

        // The stock host is often unreachable; point apt at a mirror first so
        // the very first `apt-get update` is not a two-minute timeout.
        runCatching { writeSourcesList(rootfs) }
            .onFailure { Log.w(TAG, "sources.list rewrite failed: ${it.message}") }

        Log.i(TAG, "Installing base packages into $rootfs (first boot)")
        val script = installScript()
        val result = runCatching {
            ShellExecutor.execute(context = app, command = script, timeout = BUDGET_MS)
        }.getOrNull()
        val out = result?.output.orEmpty()
        if (out.contains("MINIS_UBUNTU_BASE_OK")) {
            marker(rootfs).writeText("ok")
            Log.i(TAG, "Ubuntu base packages installed")
            true
        } else {
            Log.w(TAG, "Ubuntu base install incomplete: ${out.takeLast(400)}")
            false
        }
    }

    /**
     * Swap archive.ubuntu.com for a reachable mirror. Written as deb822 because
     * 24.04 has no `/etc/apt/sources.list` by default.
     */
    internal fun writeSourcesList(
        rootfsDir: File,
        mirrorBase: String = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/",
    ) {
        val file = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            |Types: deb
            |URIs: $mirrorBase
            |Suites: noble noble-updates noble-security
            |Components: main restricted universe multiverse
            |""".trimMargin()
        )
        // Drop the legacy one-liner so apt does not retry the unreachable host.
        File(rootfsDir, "etc/apt/sources.list").writeText("")
    }

    internal fun installScript(): String {
        val packages = BASE_PACKAGES.joinToString(" ")
        return """
            |export DEBIAN_FRONTEND=noninteractive
            |export LC_ALL=C
            |mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial
            |chmod 755 /var/lib/apt/lists /var/lib/apt/lists/partial
            |apt-get update -o Acquire::Retries=3 || exit 1
            |apt-get install -y --no-install-recommends $packages || exit 1
            |apt-get clean
            |rm -rf /var/lib/apt/lists/*
            |echo MINIS_UBUNTU_BASE_OK
            |""".trimMargin()
    }
}
