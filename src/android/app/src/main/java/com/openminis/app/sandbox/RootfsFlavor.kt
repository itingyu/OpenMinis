package com.openminis.app.sandbox

import android.content.Context

/**
 * [T-ubuntu-rootfs] Which Linux distribution the sandbox runs.
 *
 * The PRoot layer is distro-agnostic — it only needs an aarch64 rootfs with a
 * working `/bin/sh` — so adding a flavour is a matter of shipping another
 * minirootfs and teaching the few Alpine-specific spots (package manager,
 * mirrors, system prompt) about it.
 */
enum class RootfsFlavor(
    val id: String,
    /** Asset name; AAPT may strip the .gz, see RootfsManager.assetNameFor. */
    val asset: String,
    /** Directory under filesDir. Alpine keeps its historical name. */
    val dirName: String,
    val label: String,
    /** Package manager CLI. */
    val pkg: String,
    val pkgInstall: String,
    /** Distribution family used to pick mirrors and prompt wording. */
    val isAlpine: Boolean,
) {
    ALPINE(
        id = "alpine",
        asset = "alpine-minirootfs.tar.gz",
        dirName = "alpine-rootfs",
        label = "Alpine Linux",
        pkg = "apk",
        pkgInstall = "apk add",
        isAlpine = true,
    ),
    UBUNTU(
        id = "ubuntu",
        asset = "ubuntu-base-arm64.tar.gz",
        dirName = "ubuntu-rootfs",
        label = "Ubuntu 24.04",
        pkg = "apt",
        pkgInstall = "apt-get install -y",
        isAlpine = false,
    ),
    ;

    companion object {
        fun fromId(id: String?): RootfsFlavor? = entries.firstOrNull { it.id == id }

        /** The flavour a build ships by default. */
        val DEFAULT: RootfsFlavor = ALPINE
    }
}

/** [T-ubuntu-rootfs] Persisted choice; the rootfs directory follows it. */
internal object RootfsFlavorPrefs {
    private const val PREFS = "rootfs_flavor"
    private const val KEY = "flavor"

    fun current(context: Context): RootfsFlavor {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return RootfsFlavor.fromId(raw) ?: RootfsFlavor.DEFAULT
    }

    fun set(context: Context, flavor: RootfsFlavor) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, flavor.id).apply()
    }
}
