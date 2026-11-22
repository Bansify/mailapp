package com.bansify.mail.auth

/**
 * Authoritative Bansify Accounts and Bansify Mail endpoints for the `gothwad-mail` client.
 *
 * Values mirror the registration in `Bansify/accounts` and the Mail Worker contract in
 * `Bansify/mail`. Nothing here is user configurable at runtime: a wrong host would either
 * leak a bearer token or break App Link verification.
 */
object GothwadEndpoints {

    /** Public first-party OAuth client id registered for the Mail app. */
    const val CLIENT_ID: String = "gothwad-mail"

    /** Android package and application id of this client. */
    const val PACKAGE_NAME: String = "com.bansify.mail"

    /** Default (fallback) custom scheme redirect. */
    const val REDIRECT_URI_CUSTOM_SCHEME: String = "com.bansify.mail:/oauth/callback"

    /** Verified HTTPS App Link redirect. */
    const val REDIRECT_URI_APP_LINK: String = "https://mail.bansify.com/oauth/callback"

    /** Registered redirect used for the authorization request by default. */
    const val REDIRECT_URI: String = REDIRECT_URI_APP_LINK

    /** Accounts API base. */
    const val ACCOUNTS_BASE_URL: String = "https://accounts.bansify.com"

    /** Accounts API root (`/api`). */
    const val ACCOUNTS_API_BASE_URL: String = "$ACCOUNTS_BASE_URL/api"

    /** Mail Worker API base. */
    const val MAIL_API_BASE_URL: String = "https://mail.bansify.com/api"

    /** Host that may receive the Mail bearer token. Everything else must stay token free. */
    const val MAIL_API_HOST: String = "mail.bansify.com"

    /** Host that may receive the Accounts bearer token during `/oauth/userinfo` and `/oauth/revoke`. */
    const val ACCOUNTS_API_HOST: String = "accounts.bansify.com"

    /** Allowed scopes for this client, sorted the way the authorize request sends them. */
    val SCOPES: List<String> = listOf("email", "mail", "offline_access", "profile")

    val AUTHORIZE_URL: String get() = "$ACCOUNTS_API_BASE_URL/oauth/authorize"
    val SIGN_IN_CHOOSER_URL: String get() = "$ACCOUNTS_BASE_URL/signin"
    val TOKEN_URL: String get() = "$ACCOUNTS_API_BASE_URL/oauth/token"
    val USERINFO_URL: String get() = "$ACCOUNTS_API_BASE_URL/oauth/userinfo"
    val REVOKE_URL: String get() = "$ACCOUNTS_API_BASE_URL/oauth/revoke"
    val MANAGE_ACCOUNT_WEB_URL: String get() = "$ACCOUNTS_BASE_URL/me"

    /** The full scope string sent on the authorize request. */
    val SCOPE_PARAMETER: String get() = SCOPES.sorted().joinToString(" ")

    /** Path of the OAuth callback on both redirect URIs. */
    const val CALLBACK_PATH: String = "/oauth/callback"
}
