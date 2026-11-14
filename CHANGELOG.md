# Journal des modifications

## 0.12.0 — 2026-11-10 — « Miam-Miam pour CamAPS FX »

Regroupe tout le travail du 29/10 au 10/11/2026 : voix de la maison, mot-clé « miam-miam »,
dictée et voix sur le téléphone, IA par OpenRouter, nouveau nom et thème aquarelle, portions en
images, photos d'emballage et de code-barres, enseignes et factures de courses.

### Ajouté (10/11/2026, après-midi)

- **Photo d'un emballage ou d'un code-barres → base Open Food Facts du téléphone.**
  - Hors repas, la photo n'est plus refusée faute d'assiette : nom, marque et code-barres sont
    lus, puis la fiche est cherchée dans `off-base.db` (par code-barres d'abord, puis nom et
    marque).
  - En plein repas, la fiche trouvée remplace l'estimation de la ligne correspondante, ou
    ajoute le produit au repas s'il n'y était pas.
  - La marque citée passe en tête des résultats (« Carrefour Classic 8 pains au chocolat »
    rend la fiche Carrefour, et plus les pains au chocolat les plus scannés toutes marques
    confondues).
- **Réglages → « Mes enseignes & factures de courses ».**
  - Enseignes habituelles (Carrefour, E.Leclerc, Système U, Intermarché, Auchan, Lidl, Aldi,
    Casino/Monoprix/Franprix, Picard) : leurs marques propres passent en tête quand aucune
    marque n'est dite. C'est un tri, jamais un filtre.
  - Import d'une facture ou d'un ticket de caisse (PDF, photo, texte) depuis le téléphone ou
    Google Drive : les produits reconnus forment un **garde-manger personnel** consulté en
    premier ; l'enseigne lue en en-tête est cochée d'office.
- **Choix de la portion en photo à la voix.** Sans poids dit (« une assiette de riz »), la voix
  propose les paliers de la fiche (« 100, 200 ou 300 grammes ») ; la réponse peut être un
  poids, « la moyenne » ou « la deuxième ». Les puces restent touchables pendant que la voix
  parle.
- **« Cuit ou cru ? » posé par l'application.** Un poids dit sans état pour un aliment qui
  change à la cuisson (riz, pâtes, semoule…) déclenche la question, une fois par aliment.
- **Journal `MealAnnouncerMeal` : origine de chaque ligne** (`via` photo d'emballage, photo du
  tableau, facture, banque de portions, recherche ou estimation).

### Corrigé (10/11/2026, après-midi)

- **Factures : « bananes » devenaient « bananes séchées »** (84,5 g de glucides pour 100 g au
  lieu de ≈ 20). Un article n'est relié à une fiche que si son mot principal et au moins la
  moitié de ses mots y figurent, sans transformation absente de l'article (séché, compote,
  sirop, jus…), avec une valeur entre 0 et 100 g. Les fruits, légumes et herbes sans marque
  restent à la banque de portions.
- **Répondre « cru » ne changeait rien** : la mémoire anti-renommage reprenait la valeur du
  riz cuit pour « riz cru ». Un changement d'état de cuisson relance la recherche.
- La relecture qui reformule un nom (« riz » → « riz blanc cuit ») n'écarte plus la fiche de la
  banque de portions.


### Ajouté

- **Dictée et voix sur le téléphone** (`docs/VOIX-LOCALE.md`, décision 47).
  - La dictée est transcrite par Kroko (sherpa-onnx 1.13.8), avec Mistral en secours.
  - Nouveau moteur de voix « Voix locale Piper (sur le téléphone) » : voix upmc, repli
    Android.
  - Les modèles sont copiés par adb dans `files/sherpa/`.
  - L'APK est limité à arm64-v8a.

### Ajouté (10/11/2026)

- **Portions en images** (`docs/PORTIONS.md`, décision 49) : base embarquée de 33 aliments pesés
  (Nutrition5k, CC BY 4.0), carte « Quelle portion ressemble à la vôtre ? » sur chaque ligne,
  import/export de paquets `.zip` (Réglages → Portions en images), question « cuit ou à cuire ? ».

### Modifié (10/11/2026)

- **Nouveau nom : « Miam-Miam pour CamAPS FX ».** L'identifiant du paquet et l'auteur des
  entrées Nightscout (« Meal Announcer ») ne changent pas, pour ne rien casser.
- **Nouvelle allure « aquarelle » :** fond en papier aquarelle crème (grain calculé dans le code),
  lavis pastel, fruits et légumes peints en transparence, encres vert feuille, tomate et ocre,
  coins arrondis.
- Le journal `MealAnnouncerMeal` note chaque ligne du repas, le total et ce qui part dans CamAPS.

- **Toutes les IA passent par OpenRouter, avec une seule clé** (décision 48). Réglages →
  Intelligence artificielle : clé OpenRouter + « Vérifier la clé ». Dialogue, photos et
  estimations : Gemini 3.1 Flash Lite ; relecture et portions : Mistral Medium 3.1 ; dictée de
  secours : Voxtral Small. La clé Mistral ne sert plus qu'à la voix Voxtral (facultative).

- **Poids d'une pièce absent de la fiche : plus de recherche web de 17 s.**
  - Le poids estimé par le modèle sert tout de suite ; la ligne est marquée « Poids d'une
    pièce estimé » avec un bouton « Photographier l'emballage pour préciser ».
  - Le repas ne part alors pas seul vers CamAPS : l'appli le dit et propose la photo.
  - La photo (emballage ou pièces) est lue par le modèle de vision (`PackagePhoto.kt`) : poids
    imprimé d'une pièce, ou paquet ÷ nombre de pièces, glucides par pièce s'ils sont imprimés.
    Le repas est ensuite rechiffré et relu.
  - La recherche web ne sert plus que si le modèle n'a donné aucun poids.
- **Produit de marque absent d'Open Food Facts : plus de recherche web non plus** (12 à 38 s
  mesurés). L'estimation du modèle sert, la ligne est marquée « Fiche introuvable : glucides
  estimés » et la photo du tableau nutritionnel donne la vraie valeur.
- **Photo pendant un repas = photo d'emballage.** Le bouton devient « Photographier
  l'emballage » ; le modèle dit quel aliment la photo montre, et **une voix décrit ce qu'il a
  vu** (aussi affiché). Glucides pour 100 g, poids d'une pièce et quantité mangée sont appliqués,
  puis le repas est rechiffré et relu. Hors repas, la photo d'assiette décrit aussi à voix haute.
- Un aliment renommé par le modèle d'un échange à l'autre garde sa valeur (même numéro, même
  premier mot) : plus de nouvelle recherche.
- Réécoute « miam-miam » : « Yamiam », « miammiam » acceptés (jamais « Miami »).
- La relecture voit la portion imprimée de l'emballage (fausse alerte sur Prince levée).
- Open Food Facts : recherche par search-a-licious (l'ancienne API ignorait les mots cherchés).

### Corrigé (09/11/2026)

- **« Miam-miam » se déclenchait devant la télévision.** Sur 168 phrases de télé, 59 déclenchaient
  l'appli ; il n'y en a plus aucune.
  - La grammaire Vosk reçoit des mots leurres.
  - Kroko réécoute les 2,5 s avant le réveil.

### Ajouté (02/11/2026)

- **Mot-clé vocal « miam-miam » sur le téléphone** (`docs/MIAM-MIAM.md`, décision 46) —
  en une seule phrase (*« Miam-miam, je mange deux pains au chocolat »*) ou en deux temps
  (*« Miam-miam »* suivi d'une pause → *« Je t'écoute »* puis arrêt automatique au silence ~1,5 s).
  Détecteur hors ligne Vosk (`WakeWordDetector`, grammaire restreinte, modèle
  `vosk-model-small-fr-0.22` dans le dossier externe de l'appli), arbitrage du micro
  (`WakeWordArbitrator`), arrêt automatique au silence (`SpeechSilenceTracker` dans `MealAudioRecorder`),
  service au premier plan `WakeWordService` (`foregroundServiceType="microphone"`), tuile de
  raccourcis rapides Android `WakeWordTileService` (« Miam-miam »), carte « Mot-clé "miam-miam" »
  dans les réglages. « Miam-miam » est réservé au téléphone pour éviter que téléphone et enceinte
  Nabu ne se déclenchent ensemble (« Okay Nabu, je mange / je bois… » reste dédié à l'enceinte).

- **Total dicté** (`DirectCarbs`) — « Okay Nabu, annonce 25 grammes[, repas][, et note Glooko] »
  saute la recherche et le calcul ; même suite qu'après un calcul (type, CamAPS que l'utilisateur confirme,
  Carnet Glooko). Après un obstacle de l'automate (alerte CamAPS), l'enceinte le dit et donne la
  phrase qui reprend la main ; le repas compris est gardé 15 min pour la description du Carnet.
  Phrase « annonce {repas} » ajoutée à l'automatisation `assist_repas` de Home Assistant.
- Journal `MealAnnouncerHome` : dictée prise, phrases dites, questions, réponses entendues, durée de
  chaque étape du calcul, cause d'une bascule sur le téléphone.

### Corrigé

- « No answer from question » (rien entendu) n'est plus pris pour une liaison perdue : la question
  est reposée sur l'enceinte.
- La Voice PE rend la main avant d'avoir fini de parler : l'appli attend que son haut-parleur se
  taise (`awaitQuiet`) avant la phrase suivante, le micro ne s'ouvre plus sur sa propre voix.
- Une question coupée en « ? » seul faisait échouer Piper et rendait l'enceinte muette : la question
  garde toujours des mots, aucune phrase sans mots n'est envoyée.
- Écran du repas affiché par-dessus le verrouillage : l'appli présente le code ou l'empreinte quand
  CamAPS l'exige, au lieu de conclure « téléphone resté verrouillé ».

## 0.11.0 — 2026-10-29

### Ajouté

- **La voix de la maison** (`docs/VOIX-MAISON.md`) — « Okay Nabu, je mange… » dit à une enceinte
  Home Assistant ouvre le dialogue du repas, mené par cette enceinte. Module `:data:homeassistant`
  (WebSocket OkHttp, `HomeAssistantSession`, `HomeLink` avec reconnexion, accusé de réception
  immédiat), canal `HomeVoiceChannel` dans le dialogue (repli sur le téléphone si la maison se tait,
  question longue coupée en annonce + question courte, filtre anti-écho), service au premier plan
  `HomeLinkService` (notification plein écran au-dessus du verrouillage, filet de 15 s), attente du
  déverrouillage avant CamAPS, réglages « Voix de la maison » (adresse, jeton dans le Keystore, essai
  de liaison). Décisions 42 et 44.
- **Relecture des glucides** (décision 43) — un modèle de raisonnement (`magistral-medium-latest`)
  estime chaque ligne ; un écart de plus de 8 g et d'un quart est corrigé, demandé, ou bloque l'envoi
  automatique vers CamAPS. Garde-fous codés en dur (`CarbSanity`) : jamais une poudre pour une boisson,
  morceau de sucre 4 à 7 g, détail ligne par ligne dit avant le type. Conversation sur
  `mistral-large-latest`, photo sur `mistral-medium-latest`, recherche web sur `mistral-medium-latest`.
- **Deux destinations pour l'import Glooko** (décision 45) — « Nightscout classique » (conversion dans
  l'appli, inchangée) ou « Nightscout for CamAPS FX » (CSV intact en octets, aperçu puis confirmation,
  reprise selon P7).

### Corrigé

- Un thé à trois sucres comptait 123 g (fiche de thé en poudre, 10 g par morceau) : 17 g désormais.
- Un aliment de base ne part plus sur Internet (6 à 11 s par recherche) ; aliments cherchés en parallèle ;
  « Je calcule les glucides. » au-delà de 3 s.
- Après un échec de l'automate CamAPS, retour à l'accueil d'Android : CamAPS ne reste plus ouvert sur un
  menu (cause probable de son arrêt forcé par le « tueur » du téléphone le 29/10).

## 0.10.0 — 2026-10-27

### Ajouté

- **Le dialogue du repas** (`docs/DIALOGUE-REPAS.md`) — la dictée ouvre une conversation : l'IA
  garde l'histoire du repas, pose **une** question à la fois quand il manque quelque chose
  (quantité, type, sucré ou non), et accepte ajouts et corrections en cours de route. Les glucides
  restent cherchés et calculés par l'application ; un **bilan** lui est renvoyé après chaque
  réponse. `data:mistral` → `MealConversation`, `JsonAsk.history` ; `feature:assistant` →
  `MealConversationEngine`, carte « Conversation avec l'assistant ».
- **Chiffrage à la pièce par la fiche de l'emballage** — la recherche web rend les glucides d'une
  pièce, ou son poids (écrit ou déduit du paquet), et complète aussi un produit Open Food Facts
  qui ne sait pas chiffrer une pièce. L'estimation du modèle n'est plus qu'un dernier recours, et
  une ligne à 0 g fait poser une question.
- **Contester un produit** — à la question du type, « non, ce n'est pas ce produit… » reprend la
  conversation (rien n'est écrit dans CamAPS) ; bouton « Pas ce produit » sur chaque ligne. Le
  produit refusé est écarté pour tout le repas (`productIdentity`, `MealCorrection`).
- **Ordre CamAPS puis Carnet Glooko** — l'automate s'arrête sur « Confirmer », observe l'issue
  (`CamapsOutcomeTracker`), et n'écrit le Carnet que si l'annonce est confirmée, Glooko restant
  visible (un masque par-dessus empêchait Android de lire Glooko), puis revient dans Meal
  Announcer. Retour à l'onglet « Suivi » si besoin, glucides puis description, échec annoncé à
  voix haute.
- **Photo du produit sur chaque ligne** — fiche Open Food Facts (ou son code-barres), ou image de
  la page web même ; jamais pour une estimation moyenne, jamais une image d'un autre site.

### Corrigé

- **Carnet Glooko enfin écrit (validé le 27/10 à 13:36)** — le service d'accessibilité n'avait
  pas `flagReportViewIds` (aucun identifiant transmis) ; le champ glucides de Glooko
  (`EditTextWithSuffix`) plantait sans son suffixe « g » ; Glooko plantait si on le quittait juste
  après « Enregistrer » (on attend son retour à l'écran principal). Le Carnet ne reçoit plus que la
  **description** : les glucides y arrivent déjà par la synchronisation CamAPS → Glooko.
- **Deux voix disaient la même phrase** (Voxtral puis Android) sur les longues annonces, comme la
  confirmation CamAPS : la fin de lecture était attendue d'après la taille du MP3 (16 Ko/s
  supposés, ~7,5 Ko/s réels à 60 kbit/s), la lecture était coupée et jugée ratée. On attend
  désormais la durée réelle du MP3, et une lecture commencée ne déclenche plus jamais la voix
  Android.
- **Nom du produit** : sans le nombre de pièces ni le poids du paquet (« pains au chocolat
  Carrefour ») ; une photo trouvée par le web sur une page Open Food Facts est dite « Photo Open
  Food Facts ».
- **« un lait chocolaté dans une tasse » finissait à 0 g** : la règle « un/une vaut 1 » ajoutée le
  26/10 court-circuitait le dialogue de portion, et une valeur pour 100 g refusait l'unité
  « tasse ». La règle est retirée de `MealExtraction` ; dans la conversation, une mesure de ménage
  reçoit un poids estimé, affiché avec son hypothèse.

## 0.8.0 — 2026-10-26

Le repas dicté devient un **dialogue** : l'assistant demande les portions à voix haute et
comprend la réponse dite en retour — un poids, un nombre d'articles, une fraction, un
pourcentage — ou pose une question quand il manque une base.

### Ajouté

- **`core:model` → `ServingText`** — lecture des libellés de portion d'Open Food Facts :
  « 2 biscuits (25 g) » donne 2 articles et le mot « biscuit », « 25 g » ne donne rien (un poids
  n'est pas un compte), « 20 g, un biscuit » donne 1 article grâce au mot qui suit le poids.
  `gramsIn` lit « 200 g », « 1,5 kg », « 33 cl ».
- **Base embarquée enrichie** — trois colonnes de plus (`portion`, `portion_libelle`, `paquet`) :
  1 316 022 produits savent maintenant ce que pèse une portion, 843 695 le poids du paquet, et
  1 325 502 écrivent un libellé de portion. Une base construite avant ces colonnes reste lisible
  (`PRAGMA table_info` décide des colonnes à lire) : la mise à jour de l'APK n'oblige pas à
  recopier 653 Mo.
- **`data:mistral` → `PortionInterpretation`** — ce que l'analyseur déterministe ne sait pas lire
  (« une bonne poignée », « le quart d'une assiette ») est traduit par le modèle, en JSON strict,
  avec le calcul écrit en clair. Bornes 1–3000 g, comme partout ; le modèle ne donne **aucune**
  valeur de glucides.
- **`feature:assistant` → `PortionParser`** — lecture **hors ligne** des réponses courantes :
  poids, pourcentage (rapporté à la portion), fraction (« la moitié du paquet », « un quart de la
  portion »), compte d'articles (« deux biscuits ») et portion (« une portion »). Une fraction
  sans base ne se devine pas : « Un tiers de quoi ? ».
- **`SpeechAnswers`** — reconnaissance vocale Android (`SpeechRecognizer`, fr-FR, résultats
  partiels) pour la réponse, avec une sortie de secours : si le téléphone n'entend pas, le
  dialogue attend la réponse passée par le micro de l'écran (Voxtral) pendant deux minutes.
- **Le dialogue de portion** — une question à la fois, à voix haute, avec le poids retenu
  affiché sous la ligne (« Deux biscuits × 12,5 g = 25 g »). Deux silences d'affilée et l'aliment
  reste sans portion, visiblement, plutôt que d'être chiffré au hasard.
- **`core:model` → `ServingText.gramsPerItem`** — le poids d'**un** article, quand la fiche dit
  combien d'articles compte une portion : « 2 biscuits (25 g) » → 12,5 g, « 20 g, un biscuit »
  → 20 g. Un libellé qui ne nomme aucun article (« 20 g ») ne permet pas la conversion : on rend
  `null` plutôt que de supposer qu'une portion est un article.
- **La photo d'assiette** — l'écran **Repas dicté** propose **« Photographier l'assiette »** et
  **« Choisir une photo »**. La photo est réduite dans le cache de l'application (côté long
  1024 px, JPEG 80), lue par le modèle de vision, et ses aliments suivent **exactement** le
  chemin d'une phrase dictée (`MealCarbPipeline.resolveLines`). Le résultat est une
  **proposition** : « Ajouter ces aliments » ou « Écarter ».
- **`data:mistral` → `PlateInterpretation`** — le contrat de la photo : des noms seulement,
  aucune valeur de glucides, un poids de 1 à 3 000 g ou `null`, huit aliments au plus. Un aliment
  sans nom exploitable est **signalé** au lieu de disparaître ; un poids hors bornes devient
  `null` sans emporter l'aliment.
- **`docs/PHOTO-ASSIETTE.md`** — les décisions et le contrat de la photo, mis à jour une fois
  construits.

### Modifié

- **Un produit qui donne le poids d'une portion et le nombre d'articles n'est plus refusé pour
  « deux biscuits »** : `CarbCalculator` convertit alors par les glucides pour 100 g (40 g pour
  deux biscuits de 20 g). La conversion est marquée comme une convention (`assumed`), jamais
  présentée comme une valeur de la fiche. Une fiche qui ne nomme aucun article continue d'être
  refusée, avec la raison dite en clair.
- **Un libellé qui écrit l'article en toutes lettres est lu** : « Un biscuit 20 g », « Une gaufre
  70 g » (1 192 fiches de la base locale) donnent un article, donc un poids par article, alors
  qu'ils étaient refusés en mode « unité(s) ». Une fraction (« 1/2 biscuit », 6 324 fiches) n'est
  **pas** prise pour un article : on refuse plutôt que de doubler les glucides.
- **Aucune autorisation `CAMERA` n'est demandée** : l'appareil photo est appelé par
  `ACTION_IMAGE_CAPTURE`, qui n'en a pas besoin, et le fichier temporaire passe par un
  `FileProvider` limité au cache de l'application.
- **L'écriture dans CamAPS est fermée pendant le dialogue** : un total partiel ne part plus
  pendant qu'une question attend sa réponse.
- **L'écriture dans CamAPS est fermée aussi tant qu'une photo propose des aliments** : rien ne
  part avant « Ajouter ces aliments ».
- **Le micro de l'écran ne peut plus dicter un nouveau repas pendant le dialogue** : il ne sert
  qu'à répondre à la question posée.

## 0.7.0 — 2026-10-26

Le repas **dicté**, et la base Open Food Facts **embarquée**. On dit ce qu'on va manger, les
glucides sont cherchés dans une copie locale de trois millions de produits, puis Internet ne sert
qu'aux aliments qui n'y sont pas.

### Ajouté

- **`data:mistral`** — transcription (`POST /v1/audio/transcriptions`,
  `voxtral-mini-transcribe-3-0`), extraction des aliments en JSON strict
  (`/v1/chat/completions`, `mistral-small-latest`, `response_format: json_object`, température 0),
  recherche web (`POST /v1/conversations` avec l'outil `web_search`, `store: false`) et lecture du
  dernier `message.output`. Transport injecté, aucun test qui sorte sur Internet.
- **`feature:assistant`** — l'écran **Repas dicté** : on parle (ou on tape), l'application extrait
  les aliments, cherche leurs glucides, **demande les portions manquantes**, puis affiche le total.
  `AndroidAudioRecorder` enregistre 16 kHz mono dans un WAV en mémoire, jamais sur disque.
- **`data:openfoodfacts` → `OffDatabase`** — la base Open Food Facts complète du téléphone
  (3 065 819 produits, 626 Mo), cherchée **avant** le réseau : index FTS4, lecture en quelques
  dizaines de millisecondes, aucun accès Internet pour un aliment courant. Voir `docs/OFF-LOCAL.md`.
- **`OffText`** — normalisation identique à celle qui construit la base (sans quoi une recherche
  accentuée ne trouve rien), mots de mesure écartés, et `matchAttempts` : si la phrase exacte
  n'existe pas, on retire un mot à la fois en commençant par la fin, puis on se rabat sur le mot le
  plus long — en cinq essais au plus, pour ne pas renvoyer un aliment connu sur Internet.
- **`FoodNameMatcher`** — contrôle que le nom du produit trouvé désigne vraiment l'aliment demandé.
- **Provenance visible** — chaque ligne dit « Open Food Facts (hors ligne) » ou « (en ligne) » :
  on voit d'un coup d'œil si la valeur vient de la base du téléphone ou du réseau.
- **`docs/OFF-LOCAL.md`** — comment fabriquer la base, la copier, et pourquoi l'index est rempli
  par popularité.

### Corrigé

- **« Banane » chiffrée avec les glucides d'un fromage blanc.** La recherche d'Open Food Facts est
  floue et le premier résultat était pris sans contrôle. `FoodNameMatcher` refuse désormais un nom
  qui ne reprend pas les mots de l'aliment ; à défaut l'aliment part sur Internet, ou reste
  affiché comme non chiffré — jamais compté zéro.
- **« 200 grammes de riz basmati cuit » ne trouvait pas le riz.** « grammes », huit lettres, passait
  pour le mot le plus important : la recherche se rabattait dessus et rendait des purées Picard.
  Les mesures et les contenants sont maintenant écartés de la recherche.
- **La recherche pouvait prendre 17 secondes.** « chocolat » ramène 141 393 produits et il fallait
  les trier tous. L'index est désormais rempli dans l'ordre des produits les plus scannés et la
  recherche ne lit que les 2000 premières correspondances : 30 à 45 ms, quel que soit le mot.

### Modifié

- Version `0.7.0`, versionCode 8.
- **282 tests** (contre 205), aucun échec : `data:mistral` 47, `feature:assistant` 21,
  `data:openfoodfacts` 18, le reste inchangé.
- Vérifié sur le Hammer : « 200 grammes de riz basmati cuit et une banane » donne *Riz basmati
  cuit (Sévrina)* 200 g → 58,6 g et *Banane (Carrefour)* à portionner, **les deux marqués
  « hors ligne »**, total 58,6 g → 59 g pour CamAPS FX.

## 0.6.0 — 2026-10-26

La voix. Un repas confirmé est désormais **annoncé à voix haute**, avec la voix du téléphone ou
avec Voxtral (Mistral). Et le profil Nightscout s'écrit enfin sur la v3.

### Ajouté

- **`data:voxtral`** — client de la synthèse vocale : `POST /v1/audio/speech`
  (`voxtral-mini-tts-3-0`), clé en `Authorization: Bearer`, audio base64 relu depuis
  `audio_data`. Transport injecté (`HttpTransport`), erreurs classées (`UNAUTHORIZED`, `QUOTA`,
  `SERVER`, `INVALID_RESPONSE`…), aucun test qui sorte sur Internet — le module suit
  `data:nightscout` à la lettre, sans Retrofit ni kotlinx-serialization (conventions du dépôt).
  Les quatre voix françaises relevées sont dans `VoxtralVoice` (Marie par défaut).
- **`core:datastore`** — `VoiceConfig` (moteur, voix, consentement, présence de clé) avec
  `effectiveEngine()`, `VoiceConfigStore` (préférences `mealannouncer-voice`), et
  `VoxtralSecretStore` : un second coffre Keystore, alias `mealannouncer.voxtral`, distinct de
  celui de Nightscout pour qu'effacer l'un n'emporte pas l'autre.
- **`feature:voice`** — les deux moteurs (`AndroidTts`, `VoxtralTts`), l'aiguillage
  `VoiceAnnouncer`, `MealAnnouncementText` (« Repas annoncé : 62 grammes de glucides. »),
  `VoiceAudioCache` (empreinte texte+voix+modèle+format, purge au-delà de 8 Mio).
- **Réglages → section « Voix »** — choix du moteur, des quatre voix, consentement explicite à
  l'envoi du texte chez Mistral, champ de clé (« laissez vide pour conserver »), bouton « Essai
  de voix » qui dit **quel** moteur a réellement parlé.
- **Annonce au repas confirmé** — après l'enregistrement (et à part de lui), `ConfirmationViewModel`
  confie la phrase à `VoiceAnnouncer`. Une annonce ne retarde ni l'écran ni l'écriture, et son
  échec ne change rien à ce qui est en base.

### Corrigé

- **Le profil Nightscout pouvait ne jamais partir.** Sur un serveur en `authDefaultRoles: denied`,
  la clé d'API n'ouvre que les traitements ; la v3 exige un JWT. Le client échange maintenant le
  jeton d'accès contre un JWT (`/api/v2/authorization/request/<token>`), le garde jusqu'à
  `exp − 60 s` et l'envoie en `Authorization: Bearer`, tout en conservant `api-secret` pour la
  compatibilité v1 (décision 39).
- **Un échec de Voxtral pouvait être muet.** `VoxtralTts.speak` n'était pas gardé et
  `VoiceAnnouncer` avalait les exceptions sans rien retenir : l'essai affichait « Lu par la voix
  Android. » sans la moindre cause. Le moteur attrape désormais et pose `lastIssue`, l'aiguillage
  retient ce qu'il a avalé (`lastSwallowed`), et le refus d'authentification montre la réponse du
  serveur (`{"detail":"Invalid API Key"}`) au lieu du seul code 401.

### Modifié

- Version `0.6.0`, versionCode 7.
- **205 tests** (contre 167), aucun échec : `data:voxtral` 11, `feature:voice` 23,
  `core:datastore` 8, le reste inchangé.
- Vérifié sur le Hammer : Voxtral parle, le cache sert hors ligne, et sans cache le repli Android
  annonce le repas en disant pourquoi (`docs/VOXTRAL.md` § 10).

### Notes

- Le consentement Mistral est **faux par défaut** : sans lui, ou sans clé, c'est la voix Android
  qui parle — même si « Voxtral » est coché, et l'écran le dit.
- Une annonce n'attend jamais le réseau : la synthèse a un budget de 2,5 s, au-delà duquel la
  voix Android prend le relais ; la phrase tardive part au cache pour la fois suivante.
- Rien de tout cela ne touche aux garde-fous : aucune dose calculée, aucun clic automatique dans
  CamAPS FX, aucune glycémie inventée, et le texte lu ne contient que les grammes du repas.

## 0.5.1 — 2026-10-24

Deux suites du premier passage réel de l'import Glooko : une panne réseau d'une seconde ne
doit plus coûter un document, et un import interrompu doit dire ce qu'il a déjà écrit.

### Corrigé

- **Un incident réseau isolé faisait perdre un document.** Sur le premier passage du 24/10,
  326 des 331 documents sont passés et les cinq autres ont été abandonnés au premier refus,
  alors que le serveur répondait de nouveau la seconde suivante. `GlookoImporter` réessaie
  maintenant une fois les échecs **passagers** (`TIMEOUT`, `NETWORK`, `SERVER`) : deux
  tentatives au plus, deux secondes d'écart (`WRITE_ATTEMPTS`, `WRITE_RETRY_PAUSE_MS`). Un
  refus définitif (`UNAUTHORIZED`, `NOT_FOUND`…) n'est pas réessayé — il le serait pour rien —
  et la pause est réglable (`GlookoImportOptions.retryPauseMs`, zéro dans les tests).
- **Le résumé d'un import interrompu cachait son avancement.** Il disait « Impossible de
  joindre Nightscout » sans dire que 326 documents sur 331 étaient déjà écrits : on ne savait
  pas s'il fallait tout refaire. Il annonce désormais les comptes avant la cause —
  « 326 document(s) écrit(s), puis : … » — et rappelle que la relance ne double rien
  (« — relancez, rien ne sera doublé. »). Le préfixe n'apparaît que s'il y a réellement des
  écritures, et jamais en simulation.

### Modifié

- `GlookoImporterTest` : le faux client décide appel par appel s'il échoue
  (`upsertBehaviour(attempt)`) et compte les tentatives, ce qui permet de vérifier les trois
  politiques — panne passagère reprise sans perte, panne persistante avec compte partiel,
  refus définitif sans reprise. Trois tests de plus, **167** au total, aucun échec.

### Vérifié sur le Hammer

Compilation complète (`test assembleDebug`) et installation de l'APK `0.5.1` (versionCode 5)
sur le Hammer le 24/10/2026. Le serveur Nightscout a été redémarré (cinq minutes
d'indisponibilité) puis a rattrapé les glycémies xDrip+ sans trou.

### Notes

- Le rapprochement de la fenêtre et le filet de sécurité restent inchangés : le réessai ne
  remplace pas le second passage, il évite seulement de le déclencher pour rien.
- Comme en 0.5.0, le profil n'est pas envoyé (la clé d'API n'ouvre pas la v3 sur ce serveur) ;
  un token d'accès `api:*` reste nécessaire pour le rétablir.

## 0.5.0 — 2026-10-24

Import des données de thérapie Glooko XT dans Nightscout, et pilotage de l'export Glooko.

### Ajouté

- `integration:glooko` — conversion d'un export Glooko XT (CSV `;`, 31 colonnes) en
  documents Nightscout v3 :
  - `GlookoParser` : en-tête cherché dans les 60 premières lignes, fuseau et période lus
    avant l'en-tête, dates `dd/MM/yyyy HH:mm`, refus net dès qu'une ligne n'a pas ses
    31 champs — une ligne tronquée fausserait tout ce qui suit.
  - `GlookoIdentifier` : UUIDv5 (SHA-1) dans un espace de noms fixe et documenté ; le même
    évènement produit toujours le même identifiant, donc un rejeu met à jour au lieu de
    dupliquer.
  - `GlookoToNightscoutMapper` : glycémies dédupliquées par horodatage (le CSV en écrit
    deux ou trois par minute), bolus regroupés par minute (bolus normal + bolus calculé =
    un seul document, avec les glucides de la même minute), bolus étendus en `Combo Bolus`
    avec leur durée, basal une valeur par minute (la dernière gagne), évènements identifiés
    par (horodatage, libellé, durée).
  - `GlookoProfileBuilder` : profil `Glooko-CamAPS` reconstruit depuis le JSON
    `pump settings` (basal, ratio, sensibilité, cibles, durée d'insuline active, unités,
    fuseau), avec une empreinte SHA-256 du contenu — le profil n'est réécrit que s'il
    change vraiment.
  - `GlookoImporter` : deux lectures groupées (glycémies et traitements sur la fenêtre),
    suppression préalable des documents que Glooko remplace, saut des identifiants déjà
    présents, priorité **xDrip+ > Meal Announcer > Glooko**, mode simulation, état persisté
    (dernier passage, hash du profil, résumé en français).
- `integration:glookoexport` — obtention du CSV sans intervention :
  - `GlookoAccessibilityService` et `GlookoAutomation` : les étapes sont cherchées par
    **libellé**, jamais par coordonnées ; délais bornés, échec expliqué à l'utilisateur. Le
    chemin est celui relevé sur l'appareil le 24/10/2026 (Glooko XT 3.19.0) : Paramètres →
    Compte → « Export de données » (carte atteinte en faisant défiler) → intervalle de dates →
    EXPORT → notre nom dans la feuille de partage. Un mode **cartographie** écrit les libellés
    de l'interface dans `filesDir/glooko-cartographie.txt` — des libellés, aucune donnée de
    santé.
  - **Garde-fou de période** : l'écran d'export conserve des dates d'attente (2019) tant que la
    boîte de dialogue de l'intervalle n'est pas validée. L'automatisation la valide, relit les
    deux dates (`jj/MM/aaaa`) et refuse d'exporter si la borne haute a plus de deux jours
    (`DATE_RANGE`), plutôt que de produire un fichier vide pris pour un succès.
  - `GlookoExportService` : service avant-plan `dataSync` avec notification, déclenche
    l'export, puis laisse `GlookoShareActivity` faire l'import à la réception du fichier
    partagé — le CSV n'est jamais écrit dans « Téléchargements ».
  - `GlookoExportWorker` (`@HiltWorker`, trois essais) et `GlookoScheduler`
    (`PeriodicWorkRequest`, `ExistingPeriodicWorkPolicy.UPDATE`) pour le **filet de
    sécurité** : 6, 12 ou 24 h (24 h par défaut).
  - **Déclenchement par repas** : `GlookoScheduler.onMealAnnounced` est appelé quand un repas
    entre dans l'historique. Un `OneTimeWorkRequest` part trois minutes plus tard — le temps de
    saisir le bolus dans CamAPS FX — et trente minutes séparent deux déclenchements, un export
    manuel compris. L'écran de confirmation n'annonce un passage que s'il est réellement
    programmé. La cadence de quinze minutes du cahier des charges est abandonnée : elle prenait
    l'écran quatre-vingt-seize fois par jour pour des données que Glooko ne publie que toutes
    les quelques heures.
  - `GlookoCsvSource` : lecture par `MediaStore.Downloads` — aucun droit de stockage,
    plafond de 8 Mo. Utile pour un CSV téléchargé depuis le portail web, pas pour l'export de
    l'application.
  - `GlookoShareActivity` : « Partager » depuis Glooko (ou depuis l'export du portail web)
    vers Meal Announcer déclenche le même import, sans automatisation. Une activité
    transparente, et non un `BroadcastReceiver` : la feuille de partage d'Android n'énumère
    que des activités, et son filtre doit accepter `*/*` — Glooko partage son CSV sous un type
    que rien n'annonce.
- Réglages → Glooko : activation, « à chaque repas annoncé », filet de sécurité (6 / 12 / 24 h),
  fenêtre (24 / 48 / 72 / 168 h), envoi des glycémies, simulation, état du dernier passage avec
  son résumé, « Synchroniser maintenant », accès direct aux réglages d'accessibilité,
  cartographie.
- Client Nightscout : `NightscoutApi` gagne `upsert` (création ou mise à jour par
  `identifier`), `delete`, et deux lectures de plage (`entries`, `treatments`) qui
  remplacent une requête par enregistrement. **Les deux API sont supportées** : la v3 quand le
  serveur l'ouvre, la v1 sinon — le style est demandé au serveur à la première écriture, puis
  mémorisé (voir `docs/GLOOKO.md` § 8).
- 122 tests JVM de plus qu'au jalon précédent (42 → **164** au total), dont : client v3 (12),
  client v1 (14), parseur (11), identifiants (6), convertisseur (15), importateur (8) —
  doublons, ligne tronquée, fuseau inconnu, fenêtre, priorités, simulation, profil — plus
  **huit** sur les règles du déclenchement par repas.
- `docs/DECISIONS.md` : treize décisions du jalon, et `docs/GLOOKO.md` pour le mode d'emploi.

### Corrigé

Défauts trouvés en validant la chaîne sur l'appareil, le 24/10/2026 — chacun mesuré avant
d'être corrigé :

- **L'automatisation reprenait Glooko là où elle l'avait laissé.** Un second passage trouvait
  « Paramètres » déjà ouvert : l'onglet n'était plus cliquable et l'étape échouait. Chaque
  étape accepte désormais d'être déjà à l'écran, jugé sur les marqueurs de l'écran et non sur
  le chemin parcouru.
- **« Export » était ambigu.** La correspondance par sous-chaîne trouvait la carte « Export de
  données » avant le bouton `EXPORT`. Les candidats sont maintenant triés — texte exact
  d'abord — et le premier qui accepte le clic est cliqué.
- **Les dates de l'intervalle étaient refusées à tort.** Glooko affiche `2019/08/31` →
  `2019/09/15` (`aaaa/MM/jj`) avant validation, et `17/10/2026` → `24/10/2026` (`jj/MM/aaaa`)
  après. La lecture n'acceptait que la seconde forme : tout export était refusé
  (`DATE_RANGE`). Les deux formats sont lus, et les dates sont relues après validation.
- **La feuille de partage ne proposait jamais « Meal Announcer ».** Un `BroadcastReceiver`
  n'y figure pas — seules les activités y sont — et le filtre `text/csv` ne correspondait pas
  au type réellement envoyé par Glooko. Remplacé par une activité transparente avec `*/*` ;
  vérifié sur l'appareil par `cmd package query-activities` (63 paquets pour `*/*`, contre 26
  pour `text/csv`).
- **La grille de partage est défilante.** « Meal Announcer » n'apparaissait qu'après un
  défilement vertical : l'étape défile maintenant jusqu'à sa cible.
- **L'import échouait sur la lecture Nightscout** — « Nightscout a refusé l'identifiant » —
  alors que la clé était bonne : la v3 refuse la clé d'API sur ce serveur. C'est ce qui a mené
  à supporter aussi la v1 (voir `docs/GLOOKO.md` § 8 et `docs/DECISIONS.md` § 35).

### Vérifié sur le Hammer

Chaîne entière jouée deux fois le 24/10/2026 avec l'APK de debug : export piloté par
l'accessibilité, partage, import, écriture dans Nightscout en v1.

- **331 traitements** écrits, **331 identifiants distincts** — aucun doublon.
- Second passage : « rien de neuf — 331 document(s) déjà à jour · profil non envoyé (token
  d'API nécessaire) », sans une seule écriture.
- Aucune glycémie écrite (réglage désactivé) ; le profil `l'utilisateur` du serveur est resté intact.
- Un incident réseau passager a laissé le **premier** passage à 326 documents sur 331 ; le
  filet de sécurité a complété les cinq manquants quatre minutes plus tard. Le résumé du
  passage fautif dit vrai (« Impossible de joindre Nightscout »), mais il ne dit pas encore
  combien de documents étaient déjà écrits — d'où le réessai et le compte rendu partiel
  prévus en 0.5.1.

Chiffres détaillés dans `docs/GLOOKO.md` § 9.

### Notes

- Le CSV n'est pas versionné dans le dépôt : un export hebdomadaire contient une semaine de
  glycémies et d'insuline.
- Les libellés de l'interface Glooko **ont été relevés sur l'appareil** le 24/10/2026
  (Glooko XT 3.19.0) : le paquet est `com.diabnext.diabnext`, l'export s'ouvre par
  Paramètres → Compte → « Export de données », et le fichier n'existe que par la feuille de
  partage. L'intervalle de dates doit être validé avant EXPORT, sinon Glooko exporte un
  intervalle vide de 2019 — d'où le garde-fou `DATE_RANGE`. Le partage manuel reste le chemin
  qui ne dépend d'aucun libellé.
- L'automatisation prend l'écran : Glooko s'affiche une quinzaine de secondes et le téléphone
  doit être allumé et déverrouillé. Aucune API patient n'existe et l'activité d'export interne
  n'est pas exportée — c'est pourquoi le passage suit un repas (trois minutes après) plutôt
  qu'une horloge, et pourquoi la cadence de quinze minutes du cahier des charges a été
  abandonnée.
- Build validé le 24/10/2026 : `test assembleDebug` rend `BUILD SUCCESSFUL`, **164 tests**
  verts, APK de débogage de 31,7 Mo. La première compilation du jalon a révélé neuf défauts,
  tous corrigés. Deux dans le code : un import au mauvais préfixe et une chaîne `?:` qui
  perdait son type. Sept dans les tests, jamais compilés jusqu'ici : un JSON étalé sur
  plusieurs lignes dans un champ CSV, les arguments de `assertTrue` dans l'ordre de JUnit 5,
  une heure sans secondes convertie par `Instant.parse`, un helper `paris` cherché sur la
  mauvaise classe, un `plan` utilisé comme valeur au lieu d'être appelé, deux fixtures
  d'import sans ligne de réglages (donc sans profil), et un compteur de doublons attribué aux
  seules glycémies alors que les alertes répliquées comptent aussi.

## 0.4.0 — 2026-10-24

Nightscout : réglages, client, file d'attente et journal des envois.

### Ajouté

- `core:datastore` : `NightscoutConfig` (adresse, type d'identifiant, activation, simulation),
  `KeystoreSecretStore` (AES-GCM, clé dans le Keystore Android, aucune dépendance
  `security-crypto`), `NightscoutConfigStore` (préférences + `StateFlow`), `NightscoutSettings`.
- `data:nightscout` : normalisation de l'URL (`NightscoutUrl` — identifiant devant l'hôte et
  suffixe `/api/v1` retirés), `HttpNightscoutApi` (GET `status.json`, POST `treatments.json`,
  en-tête `api-secret` = SHA-1 hexadécimal pour une clé d'API), `TreatmentPayload`
  (`eventType: Carbs`, `carbs`, `notes`, `created_at`, `enteredBy`), `NightscoutRepository`,
  `NightscoutSendWorker` (WorkManager, cinq essais, délai doublé) et
  `WorkManagerNightscoutScheduler`.
- `feature:settings` : écran de réglages — adresse, `API_SECRET` ou token, activation,
  simulation, « Tester la connexion » (version et unités du serveur), « Oublier le secret ».
  L'adresse est normalisée et enregistrée telle qu'elle sera utilisée.
- Envoi automatique après confirmation (mise en file, jamais bloquant), état d'envoi affiché
  dans l'historique (« en attente », « envoyé », « échec »).
- 42 tests JVM : URL, charge utile, client (SHA-1, 401, 404, 5xx, délai, réseau, JSON
  illisible, secret absent), repository (envoi réussi, refus, simulation, désactivation,
  doublon, repas supprimé, test de connexion) et configuration.
- WorkManager reçoit ses dépendances par Hilt (`Configuration.Provider`,
  `HiltWorkerFactory`) ; l'initialiseur AndroidX par défaut est désactivé dans le manifeste.

### Notes

- Un repas déjà envoyé n'est jamais renvoyé ; un échec définitif est écrit dans le journal
  après le cinquième essai, et non silencieusement abandonné.
- La simulation ne touche ni au journal ni au réseau.

## 0.3.0 — 2026-10-24

Confirmation d'un repas et historique.

### Ajouté

- `core:database` : `MealRepository` (interface + implémentation Room) — enregistrement d'un
  repas et de ses lignes en une seule transaction, lecture des derniers repas en `Flow`,
  suppression. La note vide reçoit un libellé court (« Prince ×2 + Candy Up 200 ml »).
- `MealPayloadJson` : photo JSON complète du repas (source de chaque valeur, base de calcul,
  confiance) conservée à côté des colonnes interrogeables, pour que l'historique reste
  relisible quand le schéma évoluera.
- `feature:confirmation` : total en grand et valeur CamAPS, détail ligne par ligne (quantité,
  unité, base de calcul), aliments **non comptés** signalés en rouge avec leur avertissement,
  bouton d'enregistrement unique (aucun double appui possible), carte de succès,
  « Voir l'historique » et « Composer un autre repas ».
- `feature:history` : repas du plus récent au plus ancien — date française, total, valeur
  CamAPS, note, phrase dictée —, suppression avec confirmation, compteur au pluriel.
- `ManualEntryViewModel.confirmableMeal()` : construit le repas depuis les lignes, refuse de
  proposer un total quand aucun aliment n'est chiffré ; `clearMeal()` pour repartir à zéro.
- Bouton « Continuer » sous le total, désactivé tant qu'aucun glucide n'est calculé.
- `ResolvedMealItem.displayName()` et `shortLabel()`, avec 8 tests.
- Unités accordées en nombre dans la confirmation : « 1 unité », « 2 unités » (pluriels
  Android) au lieu de « 1 unité(s) ».
- Navigation à quatre routes (`accueil`, `saisie`, `confirmation`, `historique`) dans
  `MainActivity`, trois ViewModels portés par l'activité.

### Corrigé

- Clé d'une ligne de repas : l'index de la ligne remplace le nom du produit. Deux lignes
  identiques (« Prince ×2 » ajouté deux fois) se seraient écrasées à l'insertion.

### Vérifié sur le Hammer

Parcours complet du cas de référence : Prince × 2 + Candy Up 200 ml = **40 g**, confirmation,
enregistrement, historique, suppression d'un repas, puis fermeture et réouverture de
l'application (données conservées). 46 tests JVM au vert, 0 échec.

> Piège relevé pendant la vérification : `testDebugUnitTest` ne lance pas les tests de
> `core:model` (module Kotlin/JVM, tâche `test`). Utiliser `test`.

## 0.2.0 — 2026-10-24

Open Food Facts et saisie manuelle d'un repas.

### Ajouté

- `data:openfoodfacts` : client HTTP direct (`HttpURLConnection`, aucun bibliothèque
  réseau), recherche par code-barres et par mots, délais 8 s / 12 s, agent identifiable
  sans donnée personnelle.
- `OffMapping` : filtrage des valeurs d'Open Food Facts — glucides négatifs, supérieurs à
  100 g pour 100 g, portions absurdes et fiches sans nom écartés avant tout calcul. 9 tests.
- `DefaultFoodRepository` : Room comme source de vérité, réseau en appoint ; un produit
  vérifié n'est jamais écrasé par une fiche en ligne ; ce qui vient du réseau est mis en
  cache (la recherche suivante fonctionne hors ligne).
- `DatabaseSeeder` : quatre produits de démonstration au premier lancement, en tâche de
  fond, une seule fois.
- Écran « Composer le repas » : recherche locale instantanée (favoris d'abord), recherche
  en ligne à la demande, ajout d'un produit avec une quantité de départ plausible,
  quantité au clavier décimal, six unités, glucides recalculés à chaque frappe, total et
  valeur CamAPS, aliments non chiffrés signalés à part.
- Navigation entre l'accueil et la saisie, avec le bouton Retour du système.

### Vérifié sur le Hammer

Prince × 2 (« unité ») = 22 g, Candy Up 200 ml = 18 g, **total 40 g**, « valeur à saisir
dans CamAPS FX : 40 g » — le cas de référence du cahier des charges.

### Corrigé

- Arrondi de `Carbs` : 27,45 g donnait 27,4 à cause de la représentation binaire ;
  passage par `BigDecimal.setScale(1, HALF_UP)`.

## 0.1.0 — 2026-10-24

Première base du projet, écrite d'après le cahier des charges « Meal Announcer ».

### Ajouté

- Projet Gradle multi-modules (14 modules) : AGP 9.0.1, Gradle 9.1, Kotlin 2.3.20,
  Compose BOM 2026.03.01, JDK 21 / toolchain 17.
- Catalogue de versions centralisé (`gradle/libs.versions.toml`), versions vérifiées sur
  les dépôts Maven.
- Module `core:model` : domaine pur Kotlin — `Carbs`, `MealUnit`, `FoodProduct`,
  `MealItem`, `MealIntent`, `ResolvedMealItem`, `MealConfirmation`, statuts des trois
  destinations, `UnitConversions`, `CarbCalculator`, `MealTotals`.
- Tests JVM du domaine : cas de référence 22 g + 18 g = 40 g, quantités décimales,
  produit sans données de glucides, portion sans taille, unité inconnue, valeur saisie à
  la main, repas vide.
- Module `core:database` : entités `food_products`, `meal_logs`, `meal_items` (clé
  étrangère en cascade), trois DAO, convertisseurs d'énumérations, base Room version 1
  avec export de schéma, mappers, module Hilt.
- Jeu de données de démonstration : biscuit Prince, Candy Up chocolat, eau, pain complet.
- Application : `MealAnnouncerApp` (`@HiltAndroidApp`), `MainActivity`
  (`@AndroidEntryPoint`), thème Material 3 clair et sombre, écran d'accueil avec rappel
  médical permanent.
- Signature release : `keystore.properties` + `mealannouncer.jks` (hors dépôt),
  `proguard-rules.pro`, `build.ps1`.
- Documentation : `README.md`, `CHANGELOG.md`, cahier des charges dans `docs/`.

### État

Aucune compilation n'a encore été lancée : le premier build validera la chaîne
AGP 9 / KSP / Hilt / Room.

### À faire ensuite

- Compiler et corriger (M0).
- Saisie manuelle d'un repas (M1).
