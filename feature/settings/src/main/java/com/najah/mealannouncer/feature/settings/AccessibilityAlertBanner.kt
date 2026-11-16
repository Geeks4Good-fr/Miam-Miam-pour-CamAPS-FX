package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Bandeau d'accueil : un service d'accessibilité voulu par l'utilisateur ne tourne plus.
 *
 * Cas typique (constaté le 16/11/2026 sur Android 15) : après une mise à jour installée depuis
 * l'application, Android retire les services d'accessibilité et verrouille leur réactivation
 * (« paramètres restreints »). Sans ce bandeau, l'insertion dans CamAPS FX cessait sans bruit.
 * On dit ce qui ne marche plus, puis les deux gestes, chacun avec son bouton.
 */
@Composable
fun AccessibilityAlertBanner(
    state: SetupWizardUiState,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.showAccessibilityAlert) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.alert_access_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val what = buildList {
                if (state.camapsBroken) add(stringResource(R.string.alert_access_camaps))
                if (state.glookoBroken) add(stringResource(R.string.alert_access_glooko))
            }.joinToString("\n")
            Text(text = what, style = MaterialTheme.typography.bodyMedium)
            if (state.requiresRestrictedSettingsGuide) {
                Text(
                    text = stringResource(R.string.alert_access_steps_restricted),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.alert_access_open_app))
                }
            } else {
                Text(
                    text = stringResource(R.string.alert_access_steps),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onOpenAccessibility,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.alert_access_open_accessibility))
            }
        }
    }
}
