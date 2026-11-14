# Dicter le repas à l'enceinte — conception (28/10/2026)

**État :** conception **validée par l'utilisateur le 28/10** (propositions V1–V7 acceptées, échange entièrement
à l'enceinte). Étape 1 (HA) **faite sur le serveur** (§ 5 ter) ; étape 2 (module `:data:homeassistant`)
**faite et testée le 28/10** (§ 6) ; étapes 3–5 à faire. Le dialogue du repas
([`DIALOGUE-REPAS.md`](DIALOGUE-REPAS.md)) et la ligne rouge CamAPS ([`CAMAPS-REPAS.md`](CAMAPS-REPAS.md) § 0)
ne changent pas : seule **la bouche et l'oreille** du dialogue changent de place.

## 1. Le but

Dire « **Okay Nabu, je mange deux pains au chocolat Carrefour** » à l'enceinte de la maison, en cuisine,
téléphone dans la poche. L'enceinte mène le même dialogue que l'écran (questions de portion, bilan,
type de repas, « tu confirmes ? »). À la fin, le téléphone déverrouillé, **l'utilisateur presse lui-même
« Confirmer » dans CamAPS**, comme aujourd'hui en mode ①.

## 2. Ce qui existe déjà (vérifié le 28/10)

- **Home Assistant** 2026.10.4 (machine virtuelle) avec une enceinte **Home Assistant Voice PE**.
- Pipeline « Maison (Voxtral) » : l'oreille est **Voxtral** (parole libre, ~1 s), la voix est **Piper**
  (`fr_FR-siwis-medium`). Mot d'activation « Okay Nabu ». Secours local « Hey Jarvis » (phrases fixes
  seulement : il ne servira pas aux repas).
- Services HA de la Voice PE : `assist_satellite.announce` (dire une phrase) et
  **`assist_satellite.ask_question`** (dire une question **puis rendre ce qui a été répondu**, en texte).
- Dans l'appli, le dialogue ne parle et n'écoute qu'à travers deux points : `VoiceAnnouncer.announce`
  et `SpeechAnswers.listen` (`MealAssistantViewModel.askAndListen`, `conversationListen`, `askMealType`).
- Le Hammer n'a **pas** l'appli Home Assistant (Companion). Tailscale y est installé.

## 3. Le principe

**L'appli reste le cerveau ; Home Assistant ne fait que porter la voix.**

```
L'utilisateur ──parle──► Voice PE ──► HA (Voxtral : texte) ──événement──► Meal Announcer (Hammer)
                                                                   │ dialogue Mistral, chiffrage
L'utilisateur ◄──entend── Voice PE ◄── HA ask_question / announce ◄────────┘ (appel de service, réponse en texte)
```

1. **Phrase de départ** — une automatisation HA à déclencheur de phrase (`conversation`) avec un joker :
   « je mange {repas} », « je vais manger {repas} », « j'ai mangé {repas} », « note mon repas {repas} ».
   Elle émet l'événement **`meal_announcer_dictation`** `{texte, satellite, id}` puis attend l'accusé
   de l'appli (5 s). Sans accusé : l'enceinte dit « **Ton téléphone ne répond pas.** » et rien d'autre
   ne se passe.
2. **Le lien appli → HA** — l'appli ouvre elle-même une connexion **WebSocket** vers HA (aucune connexion
   entrante vers le téléphone, rien à ouvrir sur la box). Elle s'abonne à `meal_announcer_dictation`,
   répond par `meal_announcer_ack`, et appelle les services de la Voice PE.
3. **Le dialogue** — le même `runConversation`, avec un **canal vocal** choisi au début de la séance :
   - canal **téléphone** (aujourd'hui) : `announce` + `SpeechRecognizer` ;
   - canal **maison** (nouveau) : une question = **un seul** appel `ask_question` (dire + écouter), une
     simple phrase = `announce`. Le texte rendu par HA entre dans le dialogue exactement comme une
     réponse dite au téléphone (mêmes règles : 2 silences, 3 incompréhensions, « annule », 10 tours).
   L'écran du téléphone montre la même conversation que d'habitude, pour qui le regarde.
4. **La fin** — mêmes questions qu'aujourd'hui, posées par l'enceinte : type de repas, puis « tu
   confirmes ? ». Sur « je confirme » : l'enceinte dit « **Déverrouille ton téléphone pour confirmer dans
   CamAPS.** » ; l'automate remplit, coche le type, presse « Continuer » **et s'arrête sur la boîte
   « Confirmer »** ; L'utilisateur presse « Confirmer » ; puis Carnet Glooko. **Rien de neuf ici.**

## 4. Les décisions à prendre (proposition entre crochets)

| # | Question | Proposition |
| :-- | :-- | :-- |
| V1 | Téléphone verrouillé au moment de la phrase ? | [**Le dialogue tourne quand même**] : l'appli s'ouvre par une notification plein écran, au-dessus de l'écran verrouillé (`showWhenLocked`). Le déverrouillage n'est demandé qu'à l'étape CamAPS. |
| V2 | Comment l'appli entend HA ? | [**Service au premier plan + WebSocket**] (notification permanente discrète « Relié à la maison », reconnexion automatique). Pas d'appli Companion, pas de Firebase. |
| V3 | Quel compte HA pour l'appli ? | [**Un utilisateur HA dédié « Meal Announcer », non administrateur**], jeton longue durée rangé dans le Keystore (comme les clés Nightscout et Mistral), jamais dans les journaux. |
| V4 | Adresse de HA ? | [**Réglable**] : chacun saisit l'adresse de son Home Assistant (HTTPS recommandé, voir § 5 quater). Le canal maison est **désactivé par défaut**. |
| V5 | Quelle voix pour les questions ? | [**Piper d'abord**] (celle du pipeline). La voix « Marie » de Voxtral viendra avec le relais TTS maison, sans toucher à l'appli. |
| V6 | Phrases de départ ? | [**Celles du § 3.1**] — à compléter avec les tournures de l'utilisateur. |
| V7 | Un repas est déjà en cours sur le téléphone ? | [**Refus poli**] : « Un repas est déjà en cours sur le téléphone. » |

## 4 bis. Un échange entièrement à l'enceinte (demande de l'utilisateur, 28/10)

Questions **et réponses** passent par l'enceinte : après chaque question, la Voice PE **rouvre son micro
d'elle-même** (`ask_question`), sans redire « Okay Nabu ». Corrections, ajouts, type de repas et
« je confirme » se disent à l'enceinte. **Le seul geste sur le téléphone est « Confirmer » dans CamAPS**
(ligne rouge du mode ① ; en mode ② aussi il faut un téléphone déverrouillé, l'accessibilité n'agit pas
sur un écran verrouillé).

Fluidité visée — **3 à 5 s par échange** (Voxtral ~1 s, Mistral 1–3 s, Piper ~0,5 s) :

- **pas de carillon** avant chaque question (`preannounce: false`) ;
- **phrase d'attente** (« je cherche… ») dès qu'une recherche (fiche web, Open Food Facts) dépasse ~2 s,
  pour qu'un silence ne passe pas pour une panne ;
- **détection de fin de parole** de la Voice PE réglable (« détendue » si elle coupe les réponses
  hésitantes) ;
- limite connue : **pas de coupure de parole** — la Voice PE n'écoute pas pendant qu'elle parle.

## 5. Garde-fous propres à l'enceinte

- **Jamais de glycémie ni d'insuline** dans les phrases du repas (inchangé) ; la glycémie reste une
  phrase séparée, « quelle est ma glycémie ».
- **Une seule enceinte par repas** : celle qui a entendu la phrase de départ (`satellite`). Une autre
  pièce ne peut pas répondre à sa place.
- **Le lien tombe pendant le repas** (Wi-Fi, HA redémarre) → le dialogue **bascule sur le téléphone**
  (annonce sur le haut-parleur du téléphone : « La maison ne répond plus, je continue ici. »). Les
  lignes déjà chiffrées restent.
- **Ce que l'enceinte entend n'est jamais exécuté tel quel** : le texte passe par le même dialogue,
  la même reconnaissance du type et de la confirmation que la voix du téléphone.
- Les phrases du repas transitent par HA (dans la maison) et par Mistral (déjà le cas). Rien ne part
  ailleurs.

## 5 bis. Essais à blanc du 28/10 (étape 1) — EN PAUSE

Fait côté HA : utilisateur non admin « Meal Announcer » + jeton ; champs `input_text.meal_announcer_dictee`
(`séance|enceinte|phrase`) et `input_text.meal_announcer_accuse` (les événements personnalisés sont
réservés aux admins, d'où ces champs) ; automatisation `assist_repas` ; faux client PowerShell
(hors dépôt).

- ✅ phrase de départ → appli → accusé : fiable (Voxtral ~2 s après la fin de la phrase).
- ❌ `ask_question` : micro refermé aussitôt (firmware 25.6.0, mis à jour en 26.9.0 depuis) ; puis fin de
  parole non détectée (15 s d'écoute + 6 s Voxtral = 26 s pour « Deux ») ; question suivante **bégayée,
  robotisée**, micro jamais rouvert.
- Cause principale : **Piper trop lent dans la machine virtuelle** (3–5 s pour ~5 s de parole, PC sur batterie,
  mémoire insuffisante). À remesurer sur secteur ; sinon voix plus légère ou TTS Voxtral hors VM. Plan B si
  `ask_question` reste instable : `announce` puis réouverture du micro par HA comme après le mot
  d'activation.

## 5 ter. Bascule sur le serveur et essais du 28/10 au soir

HA tourne désormais sur un serveur distant, joint par un VPN ; un petit relais dans la maison
transmet le trafic vers la Voice PE.
Piper y est plus rapide que le temps réel (~2,3 s pour 2,8 s de parole) : **plus de bégaiement**.
Le compte « Meal Announcer » et son jeton ont été restaurés tels quels.

- 21:56 : chaîne complète OK (dictée → accusé → 2 questions → annonce, 40 s).
- 22:25 : accents abîmés par le **faux client** PowerShell (script sans BOM) — Piper lisait « Ã© »
  « A tilde copyright ». Sans objet pour l'appli Kotlin (UTF-8).
- 22:25 / 22:51 : réponses d'un mot fragiles (« un seul » → « Après. », « repas » → « Roupa. ») ; fin de
  parole non détectée (21 s) → réglage **« aggressive »** (8,9 s). Parades retenues pour l'appli :
  **réponses attendues** transmises à `ask_question` (`answers`), redemande si rien ne correspond,
  relecture de la dictée avant CamAPS ; dire à l'utilisateur d'attendre l'anneau lumineux avant de répondre.

## 5 quater. Installation du 29/10 (0.10.0-debug sur le Hammer)

- **Android bloque `http://` / `ws://`** (pas de `network_security_config` dans l'appli, targetSdk 35) :
  l'adresse Tailscale `http://100.64.0.10` ne peut pas marcher telle quelle — et Tailscale n'était pas
  lancé sur le Hammer. **Solution retenue : une adresse publique en HTTPS (`https://ha.exemple.fr`)**
  (wss derrière un proxy inverse, compte non admin, bannissement HA après 5 échecs), sans VPN.
  « Tester la liaison » : réussi le 29/10 à 00:55. Option écartée pour l'instant : autoriser le clair
  vers la seule IP Tailscale (recompilation + Tailscale toujours allumé). L'appli n'a plus
  d'adresse par défaut (`DEFAULT_HOME_URL` vide).
- Notifications de l'appli coupées à l'installation (POST_NOTIFICATIONS jamais demandée) → à activer à la
  main ; plein écran (`USE_FULL_SCREEN_INTENT`) accordé d'office.

## 6. Découpage du travail

1. **HA** (sans compiler) : utilisateur dédié + jeton ; automatisation de la phrase de départ et de
   l'accusé ; **essai à blanc** depuis le PC (un faux client WebSocket joue le rôle de l'appli) :
   phrase → événement → `ask_question` → réponse texte. Vérifie aussi les délais réels.
2. ✅ **Appli, module `:data:homeassistant`** (28/10, 26 tests verts) : `HomeAssistantClient.connect` →
   `HomeAssistantSession` (`dictations()`, `acknowledge`, `say`, `ask` avec `ExpectedAnswer`),
   `ReconnectPolicy` (1 → 60 s, jamais sur jeton refusé : HA bannit après 5 échecs), transport OkHttp
   derrière `WebSocketTransport` (faux serveur `FakeHomeAssistant` dans les tests), coffre
   `HomeAssistantSecretStore` (`core:datastore`, alias propre). Décision § 42.
3. ✅ **Appli, canal vocal** — **compilé et testé le 29/10 (132 tests verts sur les deux modules)** : `HomeVoiceChannel`
   (`feature:assistant`, sur l'interface `HomeVoiceLink` de `:data:homeassistant`) ; dans
   `MealAssistantViewModel`, toutes les phrases passent par `say()` (enceinte si elle mène le repas, sinon
   téléphone) et les trois écoutes (conversation, portion, type CamAPS) demandent d'abord à l'enceinte ;
   coupure → « La maison ne répond plus, je continue ici. » sur le téléphone et la même question y est
   reposée ; type de repas avec `MEAL_TYPE_ANSWERS` ; « Déverrouille ton téléphone… » avant CamAPS ;
   entrée `startHomeConversation(link, dictation)` (V7 « Un repas est déjà en cours… », sans clé Mistral
   l'enceinte le dit). Limite : avec Voxtral, les réponses attendues n'aident que si HA reconnaît la phrase
   entière ; « Roupa » reste incompris → la question est reposée (3 fois, puis photo). Reste pour l'étape 4 :
   attendre le déverrouillage avant d'ouvrir CamAPS.
   Conception d'origine : interface `VoiceChannel` (`say`, `ask`) ; implémentations téléphone
   (l'existant) et maison ; `runConversation` / `askMealType` / confirmation passent par le canal.
   Tests : la séance maison suit les mêmes règles d'arrêt que la séance téléphone ; repli téléphone
   sur coupure.
4. ✅ **Appli, service + réglages** — **compilé le 29/10 (APK debug construit, 141 tests verts), pas encore installé** :
   `HomeVoiceConfig`/`HomeVoiceConfigStore` (core:datastore, désactivé par défaut, adresse
   `http://100.64.0.10`) ; `HomeLink` (data:homeassistant, `@Singleton`) : `run()` tient la liaison,
   se reconnecte, accuse réception aussitôt, pose la dictée dans `pending` (prise une seule fois par
   `take`, ignorée après 2 min), sert de `HomeVoiceLink` au dialogue, `test()` pour les réglages ;
   tests `HomeLinkTest` (9). App : `HomeLinkService` (premier plan `specialUse`, notification « Relié à
   la maison », notification plein écran `ACTION_HOME_MEAL`, filet 15 s : l'enceinte dit « Je n'arrive
   pas à ouvrir l'appli… » si l'écran n'a pas pris la dictée), `HomeLinkBootReceiver` (redémarrage),
   `MainActivity` (service suit l'interrupteur, `setShowWhenLocked`/`setTurnScreenOn` le temps du repas).
   Assistant : `PhoneLock` (KeyguardManager) — à la maison, « Déverrouille ton téléphone… » puis attente
   2 min, sinon « Le téléphone est resté verrouillé. Je n'écris rien dans CamAPS. ». Réglages : section
   « Voix de la maison » (interrupteur, adresse, jeton, tester, alertes notifications / plein écran).
   Conception d'origine : service au premier plan, notification plein écran, écran au-dessus
   du verrouillage, réglages « Voix de la maison » (adresse, jeton, interrupteur, test de liaison).
5. **Essai réel** avec l'utilisateur (consignes écrites avant, cf. tests) : pain au chocolat, biscuits Prince
   (question attendue), « annule », téléphone verrouillé, coupure du Wi-Fi en cours de route.

Chaque étape appli = une compilation, **sur « Feu Vert »** de l'utilisateur.
