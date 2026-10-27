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
    // [T-ubuntu-full] Bumped so an existing base install runs the
    // install again: apt skips what is present and adds the new packages.
    private const val MARKER = ".minis-full-ok"
    private const val BUDGET_MS = 20 * 60 * 1000L
    /** Small enough that a batch finishes in a few minutes, big enough for apt. */
    private const val BATCH_SIZE = 8

    private val mutex = Mutex()

    /**
     * [T-ubuntu-full] A complete working system rather than a bare base:
     * compilers, the usual CLI surface, networking diagnostics, media tools
     * and the Python scientific stack. `ubuntu-base` ships nothing but dpkg
     * and apt, so without this the sandbox answers "command not found" for
     * almost everything a script expects.
     *
     * Installed once on first boot; ~700 MB unpacked. Users who want the
     * lean image can still run `apt-get purge` themselves.
     */
    private val BASE_PACKAGES = listOf(
        // shell & core utilities
        "bash", "coreutils", "findutils", "procps", "psmisc", "less", "file",
        "tree", "rsync", "tar", "xz-utils", "unzip", "zip", "bzip2", "gzip",
        "ca-certificates", "openssl", "locales", "tzdata", "sudo", "nano", "vim-tiny",
        // dev toolchain
        "build-essential", "pkg-config", "make", "cmake", "gcc", "g++",
        "python3-dev", "libssl-dev", "libffi-dev", "zlib1g-dev",
        // python
        "python3", "python3-pip", "python3-venv", "python3-numpy",
        "python3-pandas", "python3-requests", "python3-yaml",
        // network
        "curl", "wget", "git", "openssh-client", "dnsutils", "iproute2",
        "net-tools", "netcat-openbsd", "telnet", "sshpass",
        // text / data
        "ripgrep", "jq", "sqlite3", "gawk", "sed", "grep", "diffutils",
        // media
        "ffmpeg", "imagemagick",
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

        Log.i(TAG, "Installing packages into $rootfs (first boot)")
        val manager = RootfsManager.getInstance(app)
        val batches = BASE_PACKAGES.chunked(BATCH_SIZE)
        var done = 0
        var lastDetail = ""
        for ((index, batch) in batches.withIndex()) {
            val base = index.toFloat() / batches.size
            manager.publishInstallState(
                RootfsInstallState.Installing(base, "batch ${index + 1}/${batches.size}: ${batch.first()} …"),
            )
            val script = installScript(batch)
            val result = runCatching {
                ShellExecutor.execute(
                    context = app,
                    command = script,
                    timeout = BUDGET_MS,
                    lineCallback = { line ->
                        // apt prints one "Setting up <pkg> …" per package; using
                        // it as the detail keeps the user informed during a
                        // multi-minute batch without pretending to know a total.
                        if (line.startsWith("Setting up ")) {
                            lastDetail = line.removePrefix("Setting up ").substringBefore(' ').take(40)
                        }
                    },
                )
            }.getOrNull()
            val out = result?.output.orEmpty()
            if (!out.contains("MINIS_UBUNTU_BASE_OK")) {
                Log.w(TAG, "batch ${index + 1} incomplete: ${out.takeLast(400)}")
                manager.publishInstallState(
                    RootfsInstallState.Failed("apt failed on batch ${index + 1}"),
                )
                return@withLock false
            }
            done++
            manager.publishInstallState(
                RootfsInstallState.Installing(done.toFloat() / batches.size, lastDetail),
            )
        }
        marker(rootfs).writeText("ok")
        manager.publishInstallState(RootfsInstallState.Installed)
        Log.i(TAG, "Ubuntu packages installed")
        true
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

    internal fun installScript(packages: List<String> = BASE_PACKAGES): String {
        val joined = packages.joinToString(" ")
        return """
            |export DEBIAN_FRONTEND=noninteractive
            |export LC_ALL=C
            |mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial
            |chmod 755 /var/lib/apt/lists /var/lib/apt/lists/partial
            |apt-get update -o Acquire::Retries=3 || exit 1
            |apt-get install -y --no-install-recommends $joined || exit 1
            |apt-get clean
            |rm -rf /var/lib/apt/lists/*
            |echo MINIS_UBUNTU_BASE_OK
            |""".trimMargin()
    }
}
