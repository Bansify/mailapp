package com.gothwad.mail.auth

import android.content.Context
import android.util.AtomicFile
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistence boundary for the encrypted session, swappable in unit tests. */
interface MailTokenStore {
    fun read(): MailAuthSession
    fun write(value: MailAuthSession)
    fun clear() {}
}

/**
 * Encrypted-at-rest multi-account token store backed by Android Keystore (Tink AEAD) and an
 * `AtomicFile`, so a crash mid-write can never leave a half-written credential file.
 *
 * The key alias and the file name are bound to `packageName | clientId | authBaseUrl | redirectUri`
 * and the AEAD associated data binds the same string, so a credential file copied between apps or
 * between redirect configurations cannot be decrypted.
 */
class EncryptedTokenStore(
    context: Context,
    private val config: AuthConfig = AuthConfig.DEFAULT,
) : MailTokenStore {

    private val appContext: Context = context.applicationContext

    private val binding: ByteArray =
        "${appContext.packageName}|${config.clientId}|${config.accountsBaseUrl}|${config.redirectUri}"
            .toByteArray(Charsets.UTF_8)

    private val suffix: String = MessageDigest.getInstance("SHA-256").digest(binding)
        .joinToString("") { "%02x".format(it) }

    private val alias: String = "gothwad.mail.$suffix"
    private val file: AtomicFile by lazy { AtomicFile(File(appContext.noBackupFilesDir, "gothwad-mail-$suffix.enc")) }
    private val json = Json { ignoreUnknownKeys = true }

    private val aead by lazy {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val uri = "android-keystore://$alias"
        if (!keyStore.containsAlias(alias)) {
            AndroidKeystoreKmsClient.generateNewAeadKey(uri)
        }
        AndroidKeystoreKmsClient().getAead(uri)
    }

    override fun read(): MailAuthSession {
        val encrypted = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return MailAuthSession()
        }
        return try {
            json.decodeFromString(aead.decrypt(encrypted, binding).toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw AuthStorageException("The encrypted account store is unreadable", error)
        } catch (error: java.security.GeneralSecurityException) {
            throw AuthStorageException("The encrypted account store could not be unlocked", error)
        }
    }

    override fun write(value: MailAuthSession) {
        val encrypted = try {
            aead.encrypt(json.encodeToString(value).toByteArray(Charsets.UTF_8), binding)
        } catch (error: java.security.GeneralSecurityException) {
            throw AuthStorageException("The encrypted account store could not be written", error)
        }
        val output = try {
            file.startWrite()
        } catch (error: java.io.IOException) {
            throw AuthStorageException("The encrypted account store could not be opened", error)
        }
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw AuthStorageException("The encrypted account store could not be written", error)
        }
    }

    override fun clear() {
        file.delete()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }
}

/** In-memory store used by unit tests. */
class InMemoryTokenStore(initial: MailAuthSession = MailAuthSession()) : MailTokenStore {
    var value: MailAuthSession = initial
        private set
    var writeCount: Int = 0
        private set

    override fun read(): MailAuthSession = value

    override fun write(value: MailAuthSession) {
        this.value = value
        writeCount++
    }

    override fun clear() {
        value = MailAuthSession()
    }
}

/** Immutable configuration of this client, validated once at startup. */
data class AuthConfig(
    val packageName: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val accountsBaseUrl: String,
    val mailApiBaseUrl: String,
) {
    init {
        require(clientId.isNotBlank()) { "client_id is required" }
        require(scopes.isNotEmpty()) { "at least one scope is required" }
        val redirect = java.net.URI(redirectUri)
        require(redirect.isAbsolute && !redirect.isOpaque) { "redirect_uri must be hierarchical" }
        require(redirect.rawPath == GothwadEndpoints.CALLBACK_PATH) { "redirect_uri path must be /oauth/callback" }
        require(redirect.rawQuery == null && redirect.rawFragment == null && redirect.rawUserInfo == null)
        require(redirect.scheme == "https" || (redirect.scheme.contains('.') && redirect.rawAuthority == null)) {
            "redirect_uri must be an https App Link or a reverse-DNS custom scheme"
        }
        val accounts = java.net.URI(accountsBaseUrl)
        require(isAllowedBase(accounts)) { "accounts base must be HTTPS" }
        val mail = java.net.URI(mailApiBaseUrl)
        require(isAllowedBase(mail)) { "mail base must be HTTPS" }
    }

    companion object {
        /**
         * Only `https` is accepted for real hosts. Plain `http` is tolerated for loopback
         * addresses so unit tests can point the client at a local `MockWebServer`; a loopback
         * origin cannot be observed by another device on the network.
         */
        private fun isAllowedBase(uri: java.net.URI): Boolean {
            val host = uri.host ?: return false
            if (host.isBlank()) return false
            return when (uri.scheme) {
                "https" -> true
                "http" -> host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
                else -> false
            }
        }

        /** Production configuration for the `gothwad-mail` public client. */
        val DEFAULT: AuthConfig = AuthConfig(
            packageName = GothwadEndpoints.PACKAGE_NAME,
            clientId = GothwadEndpoints.CLIENT_ID,
            redirectUri = GothwadEndpoints.REDIRECT_URI,
            scopes = GothwadEndpoints.SCOPES,
            accountsBaseUrl = GothwadEndpoints.ACCOUNTS_API_BASE_URL,
            mailApiBaseUrl = GothwadEndpoints.MAIL_API_BASE_URL,
        )

        /** Custom-scheme fallback configuration, used when the App Link is not verified. */
        val CUSTOM_SCHEME: AuthConfig = DEFAULT.copy(redirectUri = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME)
    }
}
