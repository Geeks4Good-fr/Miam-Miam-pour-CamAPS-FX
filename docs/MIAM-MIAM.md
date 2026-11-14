# Mot-clé « miam-miam » — Conception et réalisation (02/11/2026)

**État :** implémenté, compilé et validé en réel sur le Hammer le 02/11/2026.

---

## 1. Choix retenus après essai réel (02/11/2026)

1. **Séparation nette des mots de réveil (anti-boucle téléphone ↔ enceinte)** :
   - **« Miam-miam »** est réservé au **téléphone (Hammer)**. Lors de l'essai réel, dire *« Okay Nabu, miam-miam… »* faisait déclencher simultanément l'enceinte et le micro du téléphone dans la même pièce, faisant partir les deux en boucle.
   - **« Okay Nabu »** reste le mot de réveil de **l'enceinte de la maison (Voice PE)**, suivi directement de *« je mange… »*, *« je bois… »*, *« j'ai mangé… »*, *« j'ai bu… »* ou *« annonce … »* (`assist_repas` remis sur `config_hash: e51d7491d2637dcb`).
2. **Comportement après « miam-miam » sur le téléphone (les deux modes)** :
   - **Phrase d'une traite** : *« Miam-miam, je mange deux pains au chocolat »* → coupe « miam-miam », enregistre jusqu'au silence de fin de phrase (~1,5 s), ouvre l'écran par-dessus le verrouillage et lance directement la transcription Mistral + la conversation du repas.
   - **En deux temps** : *« Miam-miam »* suivi d'une pause (~1,2 s) → ouvre l'écran par-dessus le verrouillage, annonce **« Je t'écoute. »**, écoute la dictée et **s'arrête automatiquement au silence** (~1,5 s après la parole).
3. **Activation sur le téléphone** :
   - Écoute **en permanence** (même écran éteint / verrouillé) tant que l'option est activée, via un service au premier plan (`foregroundServiceType="microphone"`).
   - Interrupteur dans l'écran **Réglages** + **Tuile de raccourcis rapides Android (`TileService`)** « Miam-miam ».

---

## 3. Fausses alertes devant la télévision (09/11/2026)

L'utilisateur a signalé que l'appli se déclenchait devant la télévision. Pour le mesurer, 168 phrases
de télévision ont été synthétisées avec 7 voix (`Miami`, `Myriam`, `mi-temps`, `ma mie`,
`j'ai mangé`, un `Miam` isolé…), ainsi que 35 phrases contenant « miam-miam ». Elles ont été
passées au même modèle Vosk, par morceaux de 125 ms, comme sur le téléphone :

| Détecteur | Phrases de télé qui déclenchent | « miam-miam » reconnus |
|---|---|---|
| Avant : grammaire `miam` + `[unk]`, résultat partiel | **59 / 168** | 35 / 35 |
| Grammaire + ~40 mots leurres (`WakeWordText.DECOY_WORDS`) | 3 / 168 | 35 / 35 |
| **Leurres + réécoute Kroko (retenu)** | **0 / 168** (et 0 sur flux continu) | 32–33 / 35 |

Cause : avec une grammaire réduite à « miam » et au jeton inconnu, Vosk ramène toute syllabe
proche à « miam ». Les mots leurres offrent un mot plus proche à la parole ordinaire.

La réécoute (`WakeWordVerifier`, implémentée par `KrokoSpeech`) fait transcrire par Kroko les
2,5 s qui précèdent la détection et 1,5 s de suite. Elle n'accepte que « miam », ou deux mots
consécutifs qui y ressemblent (`WakeWordText.verifiedByTranscript`). Les « miam-miam » manqués
venaient de voix de synthèse qui le prononçaient mal (« Milemi »). Il faut vérifier avec la
voix de l'utilisateur.

Une émission qui dit vraiment « miam-miam » déclenchera toujours : aucun mot-clé ne distingue la
voix de l'utilisateur de celle de la télévision.

Sans modèle Kroko installé, la réécoute est sautée. Les leurres suffisent alors à passer de 59 à
3 fausses alertes.

---

## 2. Ce qui a été réalisé (02/11/2026)

### A — Arrêt automatique au silence (VAD)
- `SpeechSilenceTracker.kt` (et `SpeechSilenceTrackerTest.kt`) : calcul RMS PCM 16 bits (`pcmRms`), détection de début de parole (`>= 320` RMS pendant `200 ms`), arrêt après `1 500 ms` de silence post-parole, et délai maximal de `8 000 ms` de silence initial en mode réveil en deux temps.
- `MealAudioRecorder.kt` : arrêt automatique au silence + arbitrage du micro avec `WakeWordArbitrator`.

### B — Détecteur local Vosk + Service + Tuile rapide Android
- `:core:datastore` : `WakeWordConfig.kt` (`WakeWordSettings`, `WakeWordConfigStore`) + liaison Hilt dans `DataStoreModule.kt`.
- `:feature:assistant` : `WakeWordText.kt` (et `WakeWordTextTest.kt`), `VoskModelStorage.kt` (`files/vosk-model-fr`), `WakeWordLink.kt` (`WakeWordTrigger`, `WakeWordArbitrator`, `WakeWordLink`), `WakeWordDetector.kt` (Vosk `0.3.75` à 16 kHz, grammaire restreinte, fenêtre de suite de phrase `1 200 ms`, encodage WAV `wavFromPcm16`).
- `:feature:settings` : `WakeWordSettings.kt` (`WakeWordSettingsSection`) intégré à `SettingsScreen.kt`.
- `:app` : `WakeWordService.kt` (`WakeWordService` avec `foregroundServiceType="microphone"`, `WakeWordTileService`, `WakeWordBootReceiver`), `MainActivity.kt` (`ACTION_WAKE_WORD`), `AndroidManifest.xml`.
