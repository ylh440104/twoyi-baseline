/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.ui.twin

import android.content.Context
import android.content.Intent
import io.twoyi.GuestInstanceGate
import io.twoyi.GuestInstanceInstaller
import io.twoyi.GuestInstanceManager
import io.twoyi.GuestInstanceRegistry
import io.twoyi.Render2Activity
import java.io.File

/**
 * Reads and changes the real Twin phone state.
 *
 * The Compose screens must reflect what is actually on disk, otherwise the first
 * "Create" tap would lie. This bridge is the single place that talks to the
 * instance registry, so the UI never guesses: an instance exists only if the
 * registry says so, and it is usable only if the slot holds a bootable ROM.
 */
object TwinRuntime {
    private const val DEFAULT_INSTANCE = "twin"
    private const val PREFS = "twin-shell"
    private const val KEY_PROVIDER_CONNECTED = "provider_connected"

    /** What the UI needs to know about the Twin phone. */
    data class PhoneState(
        val created: Boolean,
        val ready: Boolean,
        val busy: Boolean,
        val message: String?
    )

    fun phoneState(context: Context): PhoneState {
        val app = context.applicationContext
        val dataDir = app.dataDir
        val registry = try {
            GuestInstanceRegistry.load(
                GuestInstanceRegistry.defaultPath(dataDir.toPath())
            )
        } catch (failure: Exception) {
            return PhoneState(false, false, false, failure.message)
        }
        val id = instanceId(context)
        return try {
            val decision = GuestInstanceGate.evaluate(dataDir, registry, id)
            when (decision) {
                GuestInstanceGate.Decision.UNKNOWN_INSTANCE -> PhoneState(false, false, false, null)
                GuestInstanceGate.Decision.NO_ROM -> PhoneState(true, false, false, null)
                GuestInstanceGate.Decision.STARTABLE -> PhoneState(true, true, false, null)
            }
        } catch (failure: IllegalArgumentException) {
            PhoneState(false, false, false, failure.message)
        }
    }

    /** Create the Twin phone: reserve a slot, then install the bundled ROM into it. */
    fun createPhone(context: Context): PhoneState {
        val app = context.applicationContext
        val dataDir = app.dataDir
        val id = instanceId(context)
        return try {
            val registry = GuestInstanceRegistry.load(
                GuestInstanceRegistry.defaultPath(dataDir.toPath())
            )
            if (registry.instances().contains(id)) {
                GuestInstanceInstaller.installBundledRom(app, id)
            } else {
                GuestInstanceManager.create(dataDir.toPath(), registry, id)
                registry.activate(id)
                registry.save()
                GuestInstanceInstaller.installBundledRom(app, id)
            }
            phoneState(app)
        } catch (failure: Exception) {
            PhoneState(false, false, false, failure.message ?: failure.javaClass.simpleName)
        }
    }

    /**
     * Make [id] the active instance and open the guest window.
     *
     * The guest process resolves its slot from the active instance, so activation
     * has to happen before anything starts. `requireStartable` refuses a slot
     * without a bootable ROM, which is what keeps a tap on an uninstalled phone
     * from reaching `init` on an empty tree.
     *
     * @return null on success, otherwise a user-facing reason
     */
    fun startPhone(context: Context): String? {
        val app = context.applicationContext
        val dataDir = app.dataDir
        val id = instanceId(app)
        val registry = try {
            GuestInstanceRegistry.load(GuestInstanceRegistry.defaultPath(dataDir.toPath()))
        } catch (failure: Exception) {
            return failure.message ?: failure.javaClass.simpleName
        }
        try {
            GuestInstanceGate.requireStartable(dataDir, registry, id)
        } catch (refused: IllegalStateException) {
            return refused.message
        } catch (unknown: IllegalArgumentException) {
            return unknown.message
        }
        return try {
            registry.activate(id)
            registry.save()
            app.startActivity(
                Intent(app, Render2Activity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            null
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.simpleName
        }
    }

    /** True when the model connection was stored by this shell. */
    fun isProviderConnected(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROVIDER_CONNECTED, false)

    fun setProviderConnected(context: Context, connected: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROVIDER_CONNECTED, connected)
            .apply()
    }

    fun lastProviderLabel(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("provider_label", null)

    fun rememberProvider(context: Context, label: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("provider_label", label)
            .apply()
    }

    private fun instanceId(context: Context): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("instance_id", null)
        if (!stored.isNullOrEmpty()) return stored
        prefs.edit().putString("instance_id", DEFAULT_INSTANCE).apply()
        return DEFAULT_INSTANCE
    }

    /** Files the settings screen lists, for the "Files" entry. */
    fun dataRoot(context: Context): File =
        File(context.applicationContext.dataDir, "rootfs")
}