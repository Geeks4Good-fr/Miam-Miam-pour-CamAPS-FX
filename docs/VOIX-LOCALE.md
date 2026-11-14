# Voix locale : dictée Kroko et voix Piper sur le téléphone

**État :** implémenté le 09/11/2026 (décision 47). L'essai réel sur le Hammer reste à faire.

## 1. Pourquoi

Voxtral (Mistral) assurait deux choses : la transcription de la dictée
(`voxtral-mini-transcribe-3-0`) et la voix des annonces (`voxtral-mini-tts-3-0`). Les deux
dépendent du réseau, d'une clé et d'un consentement. Le 09/11/2026, une étude sur le Hammer a
comparé les modèles locaux exécutables par sherpa-onnx :

| Rôle | Retenu | Mesure sur le Hammer |
|---|---|---|
| Dictée | **Kroko** (zipformer en flux, français, 68 Mo) | 88 % des informations clés, ≈ 0,6 s pour 4 s de dictée, chargement ≈ 8 s (une fois) |
| Voix | **Piper fr_FR « upmc »** (medium, locutrice 0, 92 Mo avec espeak-ng-data) | 0,5 à 2,6 s de calcul par annonce, choisie à l'écoute par l'utilisateur |

Parakeet TDT 0.6B (91 %, 670 Mo) était un peu plus juste, mais dix fois plus lourd. Le même jour,
un calcul en lot sur le Hammer a saturé la mémoire et Android a tué CamAPS FX. D'où ces choix :
un modèle léger, **un seul décodage à la fois**, et deux fils de calcul.

Voxtral en local n'est pas possible : son modèle ouvert demande un GPU de 16 Go.

## 2. Fonctionnement

- **Dictée :** `MealAssistantViewModel` essaie d'abord `LocalTranscriber` (implémentation
  `KrokoSpeech` dans `app`). Si le modèle manque ou ne rend rien, la dictée part chez Mistral
  comme avant. La conversation du repas (lecture des aliments, glucides) reste chez Mistral : la
  clé reste donc nécessaire.
- **Voix :** Réglages → Voix → « Voix locale Piper (sur le téléphone) » (`VoiceEngine.PIPER`).
  Si Piper échoue, la voix Android prend le relais, comme pour Voxtral.
  - `noise_scale` est abaissé à 0,333 pour stabiliser la prononciation. Avant, « glucides »
    sortait souvent « bucide » : la réécoute donnait 7 bonnes prononciations sur 12 avant ce
    réglage, 10 sur 12 après.
  - Aucune orthographe de remplacement n'améliore « Glooko ». « CamAPS » est laissé tel quel.
- **Mot-clé « miam-miam » :** Kroko réécoute les 2,5 s qui précèdent la détection de Vosk avant
  de réveiller l'appli (voir `MIAM-MIAM.md` § 3).

Les réponses aux questions du dialogue passent encore par la reconnaissance vocale d'Android
(`AndroidSpeechAnswers`). Les faire passer par Kroko est une suite possible.

## 3. Installation des modèles (hors APK)

Les modèles sont copiés une fois par adb dans le dossier externe de l'appli. Le paquet est
`.debug` pour la variante debug.

```
D=/sdcard/Android/data/com.najah.mealannouncer.debug/files/sherpa
adb push sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/{encoder.onnx,decoder.onnx,joiner.onnx,tokens.txt} $D/kroko-fr/
adb push vits-piper-fr_FR-upmc-medium/{fr_FR-upmc-medium.onnx,tokens.txt,espeak-ng-data} $D/piper-fr-upmc/
# Indispensable : copiés par adb, les dossiers appartiennent à « shell » (drwxrws---) et l'appli
# ne peut pas les lire — la voix et la dictée locales retombent alors en silence sur Android et
# Mistral (constaté le 09/11/2026). Même réglage que le dossier Vosk.
adb shell "cd $D/.. && find sherpa -type d -exec chmod 777 {} + && find sherpa -type f -exec chmod 666 {} +"
```

Les archives se trouvent sur la page des versions de k2-fsa/sherpa-onnx :
`asr-models/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06.tar.bz2` et
`tts-models/vits-piper-fr_FR-upmc-medium.tar.bz2`.

## 4. Bibliothèque

- L'AAR officiel `sherpa-onnx-1.13.8.aar` est téléchargé depuis la version v1.13.8 de
  k2-fsa/sherpa-onnx sur GitHub. Son SHA-256 est
  `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.
- Il est posé dans `app/libs/` et exclu de Git, car il pèse 50 Mo.
- Il reste en **1.13.x** : la 2.0 a retiré espeak-ng, indispensable à Piper.
- Seul le module `app` l'embarque. Les modules `feature` ne voient que les contrats
  `LocalTranscriber`, `WakeWordVerifier` et `@PiperEngine VoiceEngineTts`.
- L'APK est limité à arm64-v8a.
- Une règle R8 garde `com.k2fsa.sherpa.onnx.**`, car le code natif lit les configurations
  par leur nom.

## 5. Licences (à vérifier avant toute diffusion)

- sherpa-onnx est sous Apache-2.0.
- La voix Piper upmc est sous la licence de son jeu de données (voir `MODEL_CARD`).
- La licence de Kroko (« Banafo Kroko-ASR ») **n'est pas encore vérifiée**. Si elle interdit
  l'usage visé, il faudra passer à Parakeet (CC-BY-4.0), chargé seulement pendant le repas.
