package dev.muve.muvi.data

import android.content.Context

/** Lo que el usuario configura. La llave y el secreto se guardan cifrados con el Keystore. */
data class Account(
    val server: String,
    val apiKey: String,
    val encrypt: Boolean,
    val secret: String,
    val mode: String, // "local" = guardar en el telefono, "online" = solo streaming con cache temporal
    val salt: String,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("muvi", Context.MODE_PRIVATE)

    val server: String get() = prefs.getString("server", "") ?: ""
    val mode: String get() = prefs.getString("mode", MODE_LOCAL) ?: MODE_LOCAL
    val encrypt: Boolean get() = prefs.getBoolean("encrypt", true)

    fun isConfigured(): Boolean = prefs.contains("key_sealed") && server.isNotEmpty()

    /** null si no hay configuracion o si el Keystore ya no puede abrirla (ej. se reinstalo la app). */
    fun load(): Account? {
        if (!isConfigured()) return null
        return try {
            Account(
                server = server,
                apiKey = SecretBox.open(prefs.getString("key_sealed", "")!!),
                encrypt = encrypt,
                secret = prefs.getString("secret_sealed", null)?.let { SecretBox.open(it) } ?: "",
                mode = mode,
                salt = prefs.getString("salt", "") ?: "",
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(a: Account) {
        prefs.edit()
            .putString("server", a.server)
            .putString("key_sealed", SecretBox.seal(a.apiKey))
            .putBoolean("encrypt", a.encrypt)
            .putString("mode", a.mode)
            .putString("salt", a.salt)
            .apply {
                if (a.encrypt) putString("secret_sealed", SecretBox.seal(a.secret)) else remove("secret_sealed")
            }
            .apply()
    }

    fun saveSalt(salt: String) {
        prefs.edit().putString("salt", salt).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val MODE_LOCAL = "local"
        const val MODE_ONLINE = "online"
    }
}
