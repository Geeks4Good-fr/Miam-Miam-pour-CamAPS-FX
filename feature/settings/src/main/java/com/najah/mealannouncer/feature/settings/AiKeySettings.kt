package com.najah.mealannouncer.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.AiSecretStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.inject.Inject

/** Ce que dit le test de la clé : rien, en cours, bon (avec le crédit restant) ou refusé. */
sealed interface AiKeyCheck {
    data object Idle : AiKeyCheck
    data object Checking : AiKeyCheck
    data class Valid(val remainingUsd: Double?) : AiKeyCheck
    data class Invalid(val reason: String) : AiKeyCheck
}

data class AiKeyUiState(
    val stored: Boolean = false,
    val input: String = "",
    val check: AiKeyCheck = AiKeyCheck.Idle,
    val message: String? = null,
    val oauthInProgress: Boolean = false,
)

data class AiKeyActions(
    val onInputChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onCheck: () -> Unit,
    val onClear: () -> Unit,
    val onConnectOAuth: (Context) -> Unit = {},
)

/**
 * Outils du flux officiel OAuth PKCE d'OpenRouter (`https://openrouter.ai/auth`), permettant de
 * récupérer une clé d'API en un clic depuis le navigateur sans copier-coller.
 */
object OpenRouterPkce {
    const val CALLBACK_URI = "miammiam://openrouter-auth"
    private const val AUTH_BASE_URL = "https://openrouter.ai/auth"
    internal const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAuthUrl(codeChallenge: String): String {
        val encodedCallback = URLEncoder.encode(CALLBACK_URI, "UTF-8")
        val encodedChallenge = URLEncoder.encode(codeChallenge, "UTF-8")
        return "$AUTH_BASE_URL?callback_url=$encodedCallback&code_challenge=$encodedChallenge&code_challenge_method=S256"
    }
}

/**
 * La clé OpenRouter : la seule dont l'appli a besoin pour ses IA (décision 48 du 10/11/2026).
 * Rangée au Keystore comme les autres secrets, jamais affichée ni journalisée.
 */
@HiltViewModel
class AiKeySettingsViewModel @Inject constructor(
    private val secrets: AiSecretStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val pkcePrefs by lazy {
        appContext.getSharedPreferences(PKCE_PREFS, Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(AiKeyUiState(stored = secrets.hasSecret()))
    val state: StateFlow<AiKeyUiState> = _state.asStateFlow()

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, message = null, check = AiKeyCheck.Idle) }
    }

    fun save() {
        val key = _state.value.input.trim()
        if (key.isEmpty()) {
            _state.update { it.copy(message = MISSING) }
            return
        }
        secrets.write(key)
        _state.update { it.copy(stored = true, input = "", message = SAVED) }
        check()
    }

    fun clear() {
        secrets.clear()
        _state.update { AiKeyUiState(stored = false, message = CLEARED) }
    }

    /**
     * Ouvre le navigateur sur la page d'autorisation OpenRouter (PKCE S256). Au retour sur
     * `miammiam://openrouter-auth?code=…`, [exchangeOAuthCode] récupère et enregistre la clé.
     */
    fun startOAuthLogin(activityContext: Context) {
        val verifier = OpenRouterPkce.generateCodeVerifier()
        pkcePrefs.edit().putString(KEY_VERIFIER, verifier).apply()
        val challenge = OpenRouterPkce.computeCodeChallenge(verifier)
        val url = OpenRouterPkce.buildAuthUrl(challenge)
        _state.update {
            it.copy(
                oauthInProgress = true,
                message = "Connexion ouverte dans le navigateur…",
                check = AiKeyCheck.Idle,
            )
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        }.onFailure {
            _state.update {
                it.copy(
                    oauthInProgress = false,
                    message = "Impossible d'ouvrir le navigateur : collez la clé ci-dessous.",
                )
            }
        }
    }

    /**
     * Échange le `code` renvoyé par OpenRouter contre une clé d'API (`POST /api/v1/auth/keys`) et
     * la chiffre dans [AiSecretStore].
     */
    fun exchangeOAuthCode(code: String) {
        val cleanCode = code.trim()
        val verifier = pkcePrefs.getString(KEY_VERIFIER, null)
        pkcePrefs.edit().remove(KEY_VERIFIER).apply()
        if (cleanCode.isEmpty() || verifier.isNullOrBlank()) {
            _state.update {
                it.copy(
                    oauthInProgress = false,
                    message = "Session de connexion expirée : recommencez en touchant « Se connecter à OpenRouter ».",
                )
            }
            return
        }
        _state.update {
            it.copy(
                oauthInProgress = true,
                message = "Récupération automatique de la clé OpenRouter…",
                check = AiKeyCheck.Checking,
            )
        }
        viewModelScope.launch {
            val exchanged = withContext(Dispatchers.IO) { requestKeyFromCode(cleanCode, verifier) }
            exchanged.fold(
                onSuccess = { key ->
                    secrets.write(key)
                    _state.update {
                        it.copy(
                            stored = true,
                            input = "",
                            oauthInProgress = false,
                            message = "Compte OpenRouter relié et clé enregistrée dans le coffre.",
                        )
                    }
                    check()
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            oauthInProgress = false,
                            check = AiKeyCheck.Invalid(err.message ?: "échange refusé"),
                            message = "La connexion automatique n'a pas abouti (${err.message}).",
                        )
                    }
                },
            )
        }
    }

    private fun requestKeyFromCode(code: String, verifier: String): Result<String> = runCatching {
        val connection = (URL(OpenRouterPkce.EXCHANGE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val payload = JSONObject()
            .put("code", code)
            .put("code_verifier", verifier)
            .put("code_challenge_method", "S256")
            .toString()
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IllegalStateException("réponse OpenRouter $status")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val key = JSONObject(body).optString("key").trim()
        if (key.isEmpty()) {
            throw IllegalStateException("aucune clé reçue d'OpenRouter")
        }
        key
    }

    /** Demande à OpenRouter si la clé est bonne et ce qu'il reste de crédit (`GET /api/v1/key`). */
    fun check() {
        val key = secrets.read()
        if (key.isNullOrBlank()) {
            _state.update { it.copy(check = AiKeyCheck.Invalid("aucune clé enregistrée")) }
            return
        }
        _state.update { it.copy(check = AiKeyCheck.Checking) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { probe(key) }
            _state.update { it.copy(check = result) }
        }
    }

    private fun probe(key: String): AiKeyCheck = try {
        val connection = (URL(KEY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $key")
        }
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).optJSONObject("data")
            val remaining = data?.takeIf { !it.isNull("limit_remaining") }?.optDouble("limit_remaining")
            AiKeyCheck.Valid(remaining?.takeIf { !it.isNaN() })
        } else {
            AiKeyCheck.Invalid(if (code == 401 || code == 403) "clé refusée par OpenRouter" else "réponse $code")
        }
    } catch (error: Exception) {
        AiKeyCheck.Invalid("OpenRouter injoignable (${error::class.simpleName})")
    }

    fun actions() = AiKeyActions(::onInputChange, ::save, ::check, ::clear, ::startOAuthLogin)

    private companion object {
        const val KEY_URL = "https://openrouter.ai/api/v1/key"
        const val TIMEOUT_MS = 10_000
        const val PKCE_PREFS = "mealannouncer-openrouter-pkce"
        const val KEY_VERIFIER = "code_verifier"
        const val MISSING = "Collez une clé avant d'enregistrer."
        const val SAVED = "Clé enregistrée."
        const val CLEARED = "Clé effacée : dialogue, photos et relecture sont arrêtés."
    }
}

@Composable
fun AiKeySettingsSection(state: AiKeyUiState, actions: AiKeyActions) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_ai_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_ai_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { actions.onConnectOAuth(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_ai_oauth_button))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_ai_oauth_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.input,
                onValueChange = actions.onInputChange,
                label = { Text(stringResource(R.string.settings_ai_key_label)) },
                placeholder = { Text("sk-or-v1-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (state.stored) R.string.settings_ai_key_stored else R.string.settings_ai_key_help,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = actions.onSave) { Text(stringResource(R.string.settings_ai_save)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = actions.onCheck, enabled = state.stored) {
                    Text(stringResource(R.string.settings_ai_check))
                }
            }
            state.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            val checkText = when (val check = state.check) {
                AiKeyCheck.Idle -> null
                AiKeyCheck.Checking -> stringResource(R.string.settings_ai_checking)
                is AiKeyCheck.Valid -> check.remainingUsd?.let {
                    stringResource(R.string.settings_ai_valid_credit, String.format(Locale.FRANCE, "%.2f", it))
                } ?: stringResource(R.string.settings_ai_valid)
                is AiKeyCheck.Invalid -> stringResource(R.string.settings_ai_invalid, check.reason)
            }
            checkText?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.check is AiKeyCheck.Invalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (state.stored) {
                TextButton(onClick = actions.onClear) { Text(stringResource(R.string.settings_ai_clear)) }
            }
        }
    }
}
