# Voxtral dans « Meal Announcer » — étude préalable

**Statut :** étude, aucune ligne de code, aucune dépendance ajoutée.
**Date :** 24/10/2026. Les versions, prix et URL ci-dessous ont été relevés ce jour-là.

**Question posée :** intégrer Voxtral (la voix de Mistral) dans la phase voix, en partant de la
page LiveKit pour Android : <https://docs.livekit.io/transport/sdk-platforms/android/>.

**Réponse courte :** LiveKit n'est pas nécessaire pour annoncer un repas à voix haute. C'est un
transport WebRTC vers une salle, et il ne fait tourner aucun modèle : Voxtral s'exécute sur un
serveur. Pour « Meal Announcer », la voie utile est un simple appel HTTPS à l'API Voxtral depuis
`feature:voice`. LiveKit ne devient intéressant que si l'on veut un vrai dialogue mains libres
(« combien de glucides dans ce plat ? »), et il ajoute alors un serveur à administrer.

---

## 1. Ce que dit la page LiveKit (et ce qu'elle ne dit pas)

### 1.1 Faits relevés

| Élément | Valeur constatée |
| :-- | :-- |
| Artefact | `io.livekit:livekit-android` |
| Dernière version | **2.29.1** (métadonnées Maven Central du 19/10/2026) |
| Variante Compose | `io.livekit:livekit-android-compose-components` |
| Dépôt supplémentaire | **JitPack obligatoire** (`com.github.davidliu:audioswitch` épinglé sur un commit) |
| Permissions | `RECORD_AUDIO` **et** `CAMERA`, demandées à l'exécution |
| Jeton d'accès | à faire générer par **un serveur** ; l'exemple de la page code en dur un jeton qui expire en 2 h |
| Licence | Apache 2.0 |

Dépendances tirées par le SDK (POM 2.29.1) : `io.github.webrtc-sdk:android-prefixed:150.7871.01`,
`okhttp 4.12.0`, `dagger 2.46` (runtime), `protobuf-javalite 3.25.9`, `javax.sip:android-jain-sip-ri`,
`semver4j`, `kotlin-stdlib-jdk7 1.9.25`, `kotlinx-coroutines-android 1.6.0`,
`kotlinx-serialization-json 1.5.0`, `com.auth0.android:jwtdecode`.

### 1.2 Le code de la page, réduit à l'essentiel

```kotlin
room = LiveKit.create(applicationContext)          // une salle
room.connect(wsURL, token)                          // jeton fourni par un serveur
room.localParticipant.setMicrophoneEnabled(true)    // publie le micro
room.events.collect { event -> … }                  // RoomEvent.TrackSubscribed, etc.
```

En Compose, la page renvoie vers les composants :

```kotlin
RoomScope(url = wsURL, token = token, audio = true, video = true, connect = true) {
    val trackRefs = rememberTracks()               // l'audio est joué automatiquement
}
```

### 1.3 Ce que la page ne dit pas, et qui décide de tout

**Le SDK Android ne contient pas Voxtral.** Il transporte de l'audio entre le téléphone et une
salle. Le modèle, lui, tourne dans un « agent » côté serveur, et le greffon Mistral de LiveKit
n'existe **qu'en Python et en Node.js** :

```python
from livekit.plugins import mistralai

session = AgentSession(
    stt=mistralai.STT(model="voxtral-mini-latest"),
    tts=mistralai.TTS(model="voxtral-mini-tts-latest", voice="…"),
)
```

Autrement dit : choisir LiveKit, c'est accepter un deuxième serveur à installer, sécuriser et
maintenir (l'agent, plus l'endpoint qui fabrique les jetons), en plus de celui de Nightscout.

---

## 2. Ce que Voxtral propose réellement

### 2.1 Synthèse vocale (TTS) — le besoin des annonces

| Élément | Valeur constatée |
| :-- | :-- |
| Point d'entrée | `POST /v1/audio/speech` |
| Modèle | `voxtral-mini-tts-3-0` (carte modèle : `voxtral-mini-tts-2603`, v26.03 du 23/03/2026) |
| Langues | 9, dont le français |
| Prix | **16 $ par million de caractères** |
| Formats de sortie | `mp3`, `wav`, `pcm`, `opus`, `flac` |
| Réponse (mode simple) | JSON, audio **encodé en base64** dans `audio_data` |
| Streaming | page dédiée « Stream speech » ; la carte annonce ~90 ms avant le premier son |
| Clonage de voix | `ref_audio` (échantillon base64), exclusif avec `voice` |
| Licence des poids | GACC BY-NC 4.0 (l'API, elle, est un service) |

Voix françaises disponibles (`voice_id`) :

| Voix | Identifiant |
| :-- | :-- |
| Marie | `voice_592` |
| Manon | `voice_3137` |
| Léa | `voice_589` |
| Camille | `voice_3141` |

Ordre de grandeur du coût pour nous : « Repas annoncé : soixante-deux grammes de glucides »
≈ 55 caractères ≈ **0,0009 $** (moins d'un dixième de centime). Six annonces par jour ≈ 0,5 c€/jour.
Avec un cache local des phrases déjà générées, le coût réel tend vers zéro.

### 2.2 Reconnaissance vocale (STT)

Deux modes, utiles pour deux besoins différents :

- **Fichier (hors ligne du point de vue du téléphone)** : un enregistrement envoyé d'un coup,
  transcription complète, horodatage, locuteurs, vocabulaire personnalisé — pour une phrase de
  trois secondes, c'est disproportionné mais simple.
- **Temps réel** : WebSocket, modèle `Voxtral Mini Transcribe Realtime 3`, détection de fin de
  tour ; c'est ce que consomme un agent LiveKit.

Le tarif STT n'a pas été relevé sur une page officielle ; la presse spécialisée cite
≈ 0,0035 $/minute pour Voxtral 3. **À vérifier sur la page tarifaire avant de s'engager.**

---

## 3. Trois architectures possibles

| | A. TTS direct depuis l'app | B. STT Voxtral pour la dictée | C. LiveKit + agent Voxtral |
| :-- | :-- | :-- | :-- |
| Ce que ça donne | l'app parle avec une voix française naturelle | on dicte le repas, le serveur transcrit | conversation mains libres, l'agent répond et relance |
| Ce qu'il faut ajouter | un client HTTPS + une clé API | un client HTTPS/WebSocket + une clé API | LiveKit auto-hébergé **ou** LiveKit Cloud, un agent Python/Node, un endpoint à jetons, le SDK Android |
| Nouveau serveur | non | non | **oui** |
| Poids dans l'APK | négligeable | négligeable | paquet WebRTC (plusieurs dizaines de Mo, toutes ABI) — à mesurer |
| Panne réseau | repli sur la voix Android | repli sur Vosk hors ligne | la conversation ne fonctionne plus du tout |
| Données envoyées | le texte de l'annonce | la voix de l'utilisateur | la voix, en continu |
| Verdict | **à faire** | option, sur accord explicite | seulement si le dialogue devient un besoin |

### 3.1 Recommandation — et décision prise

> **Décision du 24/10/2026 : la voie A est retenue.** Elle est motivée dans
> `docs/DECISIONS.md` § 36 ; le reste de cette section garde le raisonnement d'origine.

1. **Voie A maintenant** (phase voix) : annonces parlées par Voxtral, avec repli local. Aucun
   serveur, aucun LiveKit, rien qui puisse empêcher une annonce de repas.
2. **Voie B en option** : un bouton « dictée en ligne » à côté de la dictée hors ligne, désactivé
   par défaut, avec un texte clair sur ce qui part chez Mistral.
3. **Voie C plus tard, et seulement si** le dialogue apporte quelque chose que le bouton ne donne
   pas. À ce moment-là, l'agent pourra être hébergé sur le serveur qui fait déjà tourner
   Nightscout, ou chez LiveKit Cloud pour éviter l'administration.

---

## 4. Conception de la voie A, dans ce projet

### 4.1 État des lieux (vérifié dans le dépôt)

- `feature:voice` existe mais **ne contient aucun fichier Kotlin** : c'est la place libre.
- `RECORD_AUDIO` est **déjà déclaré** dans `app/src/main/AndroidManifest.xml` (prévu pour la dictée).
- `KeystoreSecretStore` (`core:datastore`) chiffre déjà des secrets avec une clé du Keystore matériel :
  la clé Mistral y va, **jamais** dans les préférences ni dans un journal.
- **Aucun moteur de synthèse n'est utilisé aujourd'hui**, et **aucun parseur de phrase française
  n'existe encore** : les types `MealIntent`, `MealItem`, `MealUnit` sont prêts, le parseur reste à
  écrire (jalon M3). La voix et sa compréhension sont donc bien devant nous, pas derrière.

### 4.2 Découpage proposé

| Morceau | Rôle |
| :-- | :-- |
| `data:voxtral` (nouveau module) | client HTTP `POST /v1/audio/speech`, DTO, traduction des erreurs — calqué sur `data:nightscout` |
| `feature:voice` | `VoiceAnnouncer` : choisit le moteur (Voxtral ou Android), met en cache, joue le son |
| `core:datastore` | réglages : moteur activé, voix choisie, cache ; clé API dans `KeystoreSecretStore` |
| `feature:settings` | écran « Voix » : moteur, voix, bouton d'essai, état du dernier échec |

### 4.3 Règles de conception

- **Le réseau ne bloque jamais une annonce.** On tente Voxtral ; au-delà d'un délai court
  (2 s pour la première syllabe), on bascule sur la voix Android et on génère le son en tâche de
  fond pour la fois suivante.
- **Cache par empreinte** : clé = `hash(texte + voix + modèle + format)`, fichiers dans le cache
  interne, purge au-delà d'une taille fixée.
- **Phrases courtes et stables** : « Repas annoncé : 62 grammes de glucides. » Les nombres sont
  écrits en lettres ou lus tels quels selon ce que la voix rend le mieux — à écouter.
- **Aucune donnée de glycémie** dans le texte envoyé : seulement les glucides du repas, qui sont
  déjà une donnée de santé. Donc réglage explicite, désactivé par défaut, et mention claire.
- **Erreurs traduites** comme pour Nightscout : `TIMEOUT`, `NETWORK`, `UNAUTHORIZED` (clé refusée),
  `QUOTA`, `SERVER` ; message court en français, jamais de texte brut du serveur.
- **Tests** : faux transport HTTP injecté, comme `NightscoutHttp` ; aucun test ne sort sur Internet.

### 4.4 Contrat technique retenu

```http
POST https://api.mistral.ai/v1/audio/speech
Authorization: Bearer <clé en Keystore>
Content-Type: application/json

{ "model": "voxtral-mini-tts-3-0",
  "input": "Repas annoncé : soixante-deux grammes de glucides.",
  "voice_id": "voice_592",
  "response_format": "mp3" }
```

Réponse : `{ "audio_data": "<base64>" }` → décodée, écrite dans un fichier temporaire, jouée par
`MediaPlayer`. (Le mode flux, s'il est retenu plus tard, évite d'attendre la fin de la génération.)

---

## 5. Si la voie C était retenue un jour

Ce que la page LiveKit impose, concrètement :

1. **Un serveur LiveKit** : auto-hébergé (Docker, port 7880 en WebSocket, 7881 en TCP, plage UDP
   pour les médias, TLS devant) ou LiveKit Cloud. À confirmer sur la page d'auto-hébergement au
   moment venu.
2. **Un endpoint à jetons** : le SDK n'accepte qu'un jeton signé avec la clé secrète du projet ;
   une application mobile ne peut donc pas se connecter sans un service qui le fabrique.
3. **Un agent** en Python ou Node.js (`livekit-agents[mistralai]`), hébergé en permanence.
4. **Le SDK Android** dans l'app : JitPack dans `settings.gradle.kts` (le projet limite aujourd'hui
   ses dépôts à Google et Maven Central, par choix), `RECORD_AUDIO` déjà présent, gestion du focus
   audio et du micro pendant l'annonce.
5. **Une mesure de l'APK** avant/après : le paquet WebRTC est le plus gros morceau.

---

## 6. Confidentialité et cadre

- Les annonces contiennent des **données de santé** (quantité de glucides, heure du repas). Les
  envoyer à un tiers est un choix qui t'appartient : il doit être explicite, réversible, et limité
  au strict nécessaire.
- Rien de ce qui concerne la glycémie, l'insuline ou le profil Nightscout n'a besoin de sortir.
- La clé API Mistral vit dans le Keystore, jamais dans un journal, un export ou une capture.
- Les poids de Voxtral sont en GACC BY-NC 4.0 : usage non commercial. L'API, elle, reste un
  service payant dont les conditions sont celles de Mistral.
- Rappel des invariants du projet : on n'envoie jamais d'insuline, on n'invente jamais une valeur,
  et une annonce ne dépend jamais d'un service externe pour aboutir.

---

## 7. Plan proposé pour la phase voix

| Étape | Contenu | Critère d'acceptation |
| :-- | :-- | :-- |
| V1 | Synthèse Android seule, phrases d'annonce, réglage on/off | une annonce est lue à voix haute, hors ligne, sans nouvelle permission |
| V2 | Client Voxtral (`data:voxtral`), clé en Keystore, écran « Voix » | la même annonce est lue par une voix française Voxtral ; sans réseau, repli immédiat |
| V3 | Cache des phrases, purge, indicateur du dernier échec | deuxième annonce identique lue sans appel réseau |
| V4 | Dictée Voxtral en option, à côté de Vosk hors ligne | désactivée par défaut ; l'écran dit ce qui est envoyé |
| V5 (si besoin) | LiveKit + agent | à décider après usage réel de V1–V4 |

Chaque étape est livrable seule, testable, et ne casse rien de l'existant.

---

## 8. Ce qui reste à trancher

Le choix d'architecture, lui, est fait (voie A, `DECISIONS.md` § 36). Reste le produit :

1. **À quoi sert la voix dans l'app ?** Relire le repas avant de l'annoncer à CamAPS, annoncer
   après l'envoi, ou les deux ? *(décision de l'utilisateur, au moment de la phase voix)*
2. **Quelle voix française ?** Marie, Manon, Léa ou Camille — il faut les écouter. À défaut,
   `voice_592` (Marie) est le choix par défaut du code.
3. **Acceptes-tu d'envoyer le texte de l'annonce à Mistral ?** (donnée de santé indirecte)
   Tant que la réponse n'est pas oui, le moteur Voxtral reste **désactivé par défaut** et seule
   la voix Android parle.
4. **La dictée reste-t-elle hors ligne** (Vosk, rien ne sort) ou passes-tu à Voxtral en ligne ?
   Le défaut est hors ligne.
5. **Clé API Mistral** : à créer sur la console, avec une date d'expiration ; elle ira dans le
   Keystore, jamais en clair dans le dépôt.

---

## 9. Sources

- LiveKit, Android quickstart : <https://docs.livekit.io/transport/sdk-platforms/android/>
  (version `.md` : <https://docs.livekit.io/transport/sdk-platforms/android.md>)
- LiveKit, Android Compose : <https://docs.livekit.io/transport/sdk-platforms/android-compose.md>
- LiveKit, greffon Mistral TTS : <https://docs.livekit.io/agents/models/tts/mistralai.md>
- LiveKit, greffon Mistral STT : <https://docs.livekit.io/agents/models/stt/mistralai.md>
- Mistral, audio (vue d'ensemble) : <https://docs.mistral.ai/studio/audio/overview>
- Mistral, synthèse vocale : <https://docs.mistral.ai/studio/audio/text-to-speech/overview>
- Mistral, génération de parole : <https://docs.mistral.ai/studio/audio/text-to-speech/generate-speech>
- Mistral, voix disponibles : <https://docs.mistral.ai/studio/audio/text-to-speech/voices>
- Mistral, reconnaissance vocale : <https://docs.mistral.ai/studio/audio/speech-to-text/overview>
- Carte du modèle Voxtral TTS (prix, licence, latence) : <https://docs.mistral.ai/models/voxtral-tts-26-03>
- Métadonnées Maven de `io.livekit:livekit-android` :
  <https://repo1.maven.org/maven2/io/livekit/livekit-android/maven-metadata.xml>

---

## 10. Ce qui est en place — 26/10/2026

Les questions du § 8 ont reçu leur réponse en séance, et le plan V1–V3 est écrit.

### Réponses retenues

| Question | Réponse |
| :-- | :-- |
| À quoi sert la voix | **Annoncer le repas après son enregistrement** (pas de relecture avant, pas de dialogue) |
| Quelle voix | `voice_592` (Marie) par défaut ; les quatre sont sélectionnables dans les réglages |
| Envoyer le texte à Mistral | **Oui, mais explicitement** : une case à cocher, fausse par défaut (`consentMistral`) |
| Dictée | Reste hors ligne (Vosk) ; aucune dictée Voxtral dans cette version |
| Clé Mistral | Saisie dans l'app, écrite dans le Keystore `mealannouncer.voxtral`, jamais affichée |

### Modules livrés

| Module | Contenu |
| :-- | :-- |
| `data:voxtral` | `VoxtralApi`/`HttpVoxtralApi`, `SpeechRequest`, `VoxtralVoice` (4 voix), `VoxtralAudioFormat`, `VoxtralException` classée |
| `core:datastore` | `VoiceConfig` + `effectiveEngine()`, `VoiceSettings`, `VoiceConfigStore`, `VoxtralSecretStore` |
| `feature:voice` | `VoiceEngineTts` (+ qualifiers `@AndroidEngine`/`@VoxtralEngine`), `AndroidTts`, `VoxtralTts`, `VoiceAnnouncer`, `MealAnnouncementText`, `VoiceAudioCache`, `VoiceCatalog` |
| `feature:settings` | Section « Voix » : moteur, voix, consentement, clé, essai |
| `feature:confirmation` | Annonce après enregistrement, lancée à part pour ne rien retarder |

### Le chemin complet d'une annonce

1. Le repas est confirmé → `ConfirmationViewModel.save()` écrit en base, met Nightscout en file,
   programme l'export Glooko.
2. L'écran affiche « enregistré » **immédiatement** ; l'annonce part dans une coroutine séparée.
3. `VoiceAnnouncer` regarde `VoiceConfig.effectiveEngine()` : Voxtral seulement si moteur +
   consentement + clé.
4. Si Voxtral : cache d'abord (`cacheDir/voxtral/<sha256>.mp3`), sinon synthèse avec un budget de
   **2,5 s**, puis lecture `MediaPlayer` sur le fil principal. Au-delà du budget, ou sur échec,
   on rend `false` sans exception.
5. Repli : `AndroidTts` lit la même phrase, hors ligne. Si la voix système manque, rien n'est dit
   — et l'écran d'essai le signale.

### Comment vérifier à la main

1. Réglages → Voix : laisser « Voix Android », appuyer **Essai de voix** → la phrase doit être
   entendue et l'écran afficher « Lu par la voix Android. » (fonctionne sans réseau).
2. Coller la clé Mistral, cocher le consentement, choisir « Voix Voxtral », **Essai de voix** →
   « Lu par Voxtral. » ; couper ensuite le réseau et refaire l'essai : la phrase doit sortir du
   cache, ou être lue par Android, mais **jamais** rester muette.
3. Décocher le consentement : l'écran doit dire que la voix Android remplacera Voxtral, et
   l'essai doit le confirmer.

### Ce qui reste

- **Écouter les quatre voix** et arrêter le choix par défaut.
- **V4** (dictée Voxtral) : non commencée, et volontairement à côté du chemin d'annonce.
- **Rotation de la clé** si elle a circulé hors du Keystore.

### Vérifié sur l'appareil — 26/10/2026, 6 h 30

Tout ce qui précède a été observé sur le Hammer Iron 6 5G (Android 15), APK `0.6.0-debug` :

| Essai | Résultat observé |
| :-- | :-- |
| Voix Android seule (moteur Android) | « Lu par la voix Android. », phrase entendue |
| Voix Voxtral, clé + consentement | « Lu par Voxtral. » ; `cache/voxtral/31c3d5….mp3` (16–20 kio) écrit |
| Réseau coupé, cache présent | « Lu par Voxtral. » — l'annonce sort du cache, sans réseau |
| Réseau coupé, cache effacé | « Lu par la voix Android. » + « Voxtral : Voxtral injoignable » |
| Consentement décoché | « Moteur réellement employé : voix Android. » et l'essai le confirme |
| Réseau rétabli | « Lu par Voxtral. », cache réécrit |

**Piège qui a coûté une heure** : la clé saisie par `adb shell input text` en deux morceaux avait
**46 caractères au lieu de 45** ; Mistral répondait `401 {"detail":"Invalid API Key"}`. La clé
elle-même était bonne (le même appel passait depuis le PC). Le diagnostic décisif a été
d'imprimer, côté appareil, la longueur et les 8 premiers hexadécimaux du SHA-256 de la clé lue
dans le Keystore, à comparer avec la même empreinte calculée sur le PC : 46 car. / `d8745ca4`
contre 45 car. / `c6e7f07e`. Saisir la clé **d'un seul `input text`** a suffi.

