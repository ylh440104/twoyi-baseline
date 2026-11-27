/* SPDX-License-Identifier: MPL-2.0 */
package io.twoyi.ui.twin

import android.content.Context
import android.content.Intent
import io.twoyi.GuestInstanceGate
import io.twoyi.GuestInstanceInstaller
import io.twoyi.GuestInstanceManager
import io.twoyi.GuestInstanceRegistry
import io.twoyi.Render2Activity
import io.twoyi.TwoyiStatusManager
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
    const val FLAG_SEE_SCREEN = "see_screen"
    const val FLAG_SENSITIVE_GUARD = "sensitive_guard"
    const val FLAG_CONFIRM_RISKY = "confirm_risky"
    private const val FLAG_MEMORY = "memories"
    private const val DEFAULT_INSTANCE = "twin"
    private const val PREFS = "twin-shell"
    private const val KEY_PROVIDER_CONNECTED = "provider_connected"
    private const val KEY_PROVIDER_MODEL = "provider_model"
    private const val KEY_PROVIDER_URL = "provider_url"
    private const val KEY_PROVIDER_SECRET = "provider_secret"

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

    /** What the guest process reports right now. */
    enum class GuestPhase { STOPPED, STARTING, RUNNING, FAILED }

    fun guestPhase(): GuestPhase = when (TwoyiStatusManager.getInstance().phase()) {
        TwoyiStatusManager.Phase.STOPPED -> GuestPhase.STOPPED
        TwoyiStatusManager.Phase.STARTING -> GuestPhase.STARTING
        TwoyiStatusManager.Phase.RUNNING -> GuestPhase.RUNNING
        TwoyiStatusManager.Phase.FAILED -> GuestPhase.FAILED
    }

    /**
     * Open the guest window again without restarting it.
     *
     * Once the phone is running, "use phone" must not go through the start gate:
     * that path would refuse (or restart) a live guest. The newest build reuses
     * the running session the same way.
     */
    fun reopenPhone(context: Context): String? {
        val app = context.applicationContext
        return try {
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
        prefs(context).getString(KEY_PROVIDER_MODEL, null)

    fun lastProviderUrl(context: Context): String? =
        prefs(context).getString(KEY_PROVIDER_URL, null)

    /** Full connection, with the key decrypted when it can be opened. */
    data class ProviderConfig(val baseUrl: String, val model: String, val key: String?)

    fun providerConfig(context: Context): ProviderConfig? {
        val model = lastProviderLabel(context) ?: return null
        val envelope = prefs(context).getString(KEY_PROVIDER_SECRET, null)
        return ProviderConfig(
            baseUrl = lastProviderUrl(context).orEmpty(),
            model = model,
            key = envelope?.let { TwinSecretStore.open(context, it) }
        )
    }

    /**
     * Store a connection. The key only counts as saved when the keystore sealed it;
     * otherwise the caller must report `twin_key_save_failed` instead of pretending.
     */
    fun rememberProvider(context: Context, baseUrl: String, model: String, key: String): Boolean {
        val envelope = TwinSecretStore.seal(context, key)
        prefs(context).edit()
            .putString(KEY_PROVIDER_MODEL, model)
            .putString(KEY_PROVIDER_URL, baseUrl)
            .apply()
        if (envelope == null) return false
        prefs(context).edit().putString(KEY_PROVIDER_SECRET, envelope).apply()
        setProviderConnected(context, true)
        return true
    }

    private fun instanceId(context: Context): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("instance_id", null)
        if (!stored.isNullOrEmpty()) return stored
        prefs.edit().putString("instance_id", DEFAULT_INSTANCE).apply()
        return DEFAULT_INSTANCE
    }

    /** Toggles the settings screen exposes; all default to the safest choice. */
    fun isFlag(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, false)

    fun setFlag(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun memories(context: Context): List<String> =
        prefs(context).getStringSet(FLAG_MEMORY, emptySet())!!.sorted()

    fun remember(context: Context, note: String) {
        if (note.trim().isEmpty()) return
        val current = prefs(context).getStringSet(FLAG_MEMORY, emptySet())!!.toMutableSet()
        current.add(note.trim())
        prefs(context).edit().putStringSet(FLAG_MEMORY, current).apply()
    }

    fun forget(context: Context, note: String) {
        val current = prefs(context).getStringSet(FLAG_MEMORY, emptySet())!!.toMutableSet()
        current.remove(note)
        prefs(context).edit().putStringSet(FLAG_MEMORY, current).apply()
    }

    fun forgetAll(context: Context) {
        prefs(context).edit().remove(FLAG_MEMORY).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Files the settings screen lists, for the "Files" entry. */
    fun dataRoot(context: Context): File =
        File(context.applicationContext.dataDir, "rootfs")
}