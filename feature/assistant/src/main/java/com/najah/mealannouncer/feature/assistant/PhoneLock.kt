package com.najah.mealannouncer.feature.assistant

import android.app.KeyguardManager
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le téléphone est-il verrouillé ?
 *
 * Le service d'accessibilité ne peut pas agir dans CamAPS sur un écran verrouillé : un repas dicté
 * à la maison attend donc que l'utilisateur déverrouille avant d'ouvrir CamAPS (`docs/VOIX-MAISON.md` § 3.4).
 */
fun interface PhoneLock {
    fun isLocked(): Boolean
}

@Singleton
class KeyguardPhoneLock @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhoneLock {
    override fun isLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhoneLockModule {
    @Binds
    @Singleton
    abstract fun bindPhoneLock(impl: KeyguardPhoneLock): PhoneLock
}
