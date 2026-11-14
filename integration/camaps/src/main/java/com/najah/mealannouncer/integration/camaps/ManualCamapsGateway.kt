package com.najah.mealannouncer.integration.camaps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Les noms de paquets sous lesquels CamAPS FX a été vu.
 *
 * Trois noms circulent selon la version et le canal de distribution : les essayer dans
 * l'ordre évite de dépendre d'une installation particulière.
 */
object CamapsPackages {

    val CANDIDATES: List<String> = listOf(
        "com.camdiab.fx_alert.mgdl",
        "com.camdiab.fx.alert.mgdl",
        "com.camdiab.fx.alert",
    )

    /** Le paquet installé, ou `null` si CamAPS FX n'est pas sur ce téléphone. */
    fun installed(packageManager: PackageManager): String? = CANDIDATES.firstOrNull { name ->
        try {
            packageManager.getPackageInfo(name, 0)
            true
        } catch (error: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** L'intention qui ouvre l'application, ou `null` si elle n'est pas installée. */
    fun launchIntent(packageManager: PackageManager): Intent? =
        installed(packageManager)?.let { packageManager.getLaunchIntentForPackage(it) }
}

/**
 * La porte « manuelle » vers CamAPS : ouvre l'application et laisse l'utilisateur saisir.
 *
 * C'est le comportement par défaut, et il reste le filet de sécurité de l'automatisation :
 * l'application n'écrit jamais dans CamAPS sans que l'utilisateur ait lancé le geste, et
 * elle ne valide jamais à sa place.
 */
class ManualCamapsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : CamapsMealGateway {

    override suspend fun writeCarbs(carbs: Int): CamapsWriteResult {
        if (carbs <= 0) {
            return CamapsWriteResult.Failed("Aucun glucides à écrire dans CamAPS.")
        }
        val intent = CamapsPackages.launchIntent(context.packageManager)
            ?: return CamapsWriteResult.Failed("CamAPS FX n'est pas installé sur ce téléphone.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return withContext(Dispatchers.Main) {
            try {
                context.startActivity(intent)
                CamapsWriteResult.Manual(carbs)
            } catch (error: Exception) {
                // Un lancement refusé (application désactivée, profil de travail…) se dit,
                // il ne se tait pas : l'utilisateur croirait sinon que la valeur est partie.
                CamapsWriteResult.Failed(
                    "CamAPS FX n'a pas pu être ouvert : ${error.message ?: "cause inconnue"}",
                )
            }
        }
    }

    /**
     * Le chemin manuel ne sait pas remplir ni cocher : il ouvre CamAPS et laisse l'utilisateur saisir.
     * Le type et la confirmation vocale ne servent qu'à la porte automatique.
     */
    override suspend fun announceMeal(request: CamapsMealRequest): CamapsWriteResult =
        writeCarbs(request.carbs)
}
