# Miam-Miam pour CamAPS FX

*Nom de code du projet : **Meal Announcer** (identifiant `com.najah.mealannouncer`).*

Une application Android pour les personnes diabétiques de type 1 en boucle fermée **CamAPS FX** :
on décrit son repas à voix haute — *« Miam-miam, deux pains au chocolat et un verre de lait »* —
et l'application trouve les glucides, les dit, puis les inscrit dans CamAPS FX, dans le Carnet
Glooko et, si on le souhaite, dans Nightscout.

> **Version 0.14.0 (14 novembre 2026) — Installation tout-en-un sans ordinateur.** Le détail de
> chaque version est dans [`CHANGELOG.md`](CHANGELOG.md), les raisons de chaque choix dans
> [`docs/DECISIONS.md`](docs/DECISIONS.md) (49 décisions).

---

## Sommaire

1. [Pourquoi cette application](#pourquoi-cette-application)
2. [Installer et configurer en 4 étapes (sans ordinateur)](#installer-et-configurer-en-4-étapes-sans-ordinateur)
3. [Ce qu'elle ne fait pas — et ne fera pas](#ce-quelle-ne-fait-pas--et-ne-fera-pas)
4. [Un repas, du début à la fin](#un-repas-du-début-à-la-fin)
5. [D'où viennent les glucides](#doù-viennent-les-glucides)
6. [Les fonctions une par une](#les-fonctions-une-par-une)
7. [Les réglages](#les-réglages)
8. [Données personnelles, réseau et coûts](#données-personnelles-réseau-et-coûts)
9. [Suivre ce qui se passe (journaux)](#suivre-ce-qui-se-passe-journaux)
10. [Pour les développeurs](#pour-les-développeurs)
11. [Avertissement médical](#avertissement-médical)
12. [Licences et crédits](#licences-et-crédits)

---

## Pourquoi cette application

Avec CamAPS FX, l'algorithme gère l'insuline, mais il faut lui **annoncer les glucides** de
chaque repas. Compter les glucides est la tâche la plus pénible du quotidien : chercher la
valeur, peser, faire la règle de trois, puis tout saisir à la main dans CamAPS, puis encore dans
le carnet du diabétologue.

Miam-Miam fait ce travail de comptage et de saisie :

- **on parle** au lieu de taper : la dictée fonctionne sur le téléphone, sans Internet ;
- **l'application cherche** les valeurs dans de vraies bases (3 millions de produits du commerce,
  une banque de portions pesées, vos propres courses) et **ne devine qu'en dernier recours**, en le
  disant ;
- **une conversation** fait préciser ce qui manque (« quelle quantité ? », « cuit ou cru ? »), une
  question à la fois ;
- **un second avis** (une autre IA, de type « diététicien ») relit chaque repas avant l'annonce ;
- **la saisie** dans CamAPS FX et dans le Carnet Glooko est faite par l'application — mais c'est
  **vous** qui confirmez dans CamAPS.

---

## Installer et configurer en 4 étapes (sans ordinateur)

Depuis la version **0.14.0**, toute l'installation se fait **directement sur le téléphone**, sans
ordinateur, sans câble USB et sans ligne de commande.

### 1. Installer l'APK sur le téléphone

Télécharger **`Miam-Miam-0.14.0-arm64.apk`** depuis la page des
[versions publiées](https://github.com/Geeks4Good-fr/Miam-Miam-pour-CamAPS-FX/releases) directement
dans le navigateur du téléphone et l'ouvrir (Android demande d'autoriser l'installation depuis cette
source).

> Sur une installation déjà existante, **tous vos réglages, votre clé OpenRouter et votre
> garde-manger sont conservés** lors de la mise à jour.

### 2. Suivre l'Assistant d'installation intégré (4 étapes)

Au premier lancement — et à tout moment tout en haut de l'écran **Réglages** — l'**Assistant
d'installation pas à pas** prépare votre téléphone :

| Étapes 1 et 2 (OpenRouter 1 clic & téléchargement hors ligne) | Étapes 3 et 4 (Autorisations & Accessibilité CamAPS / Glooko) |
| :-: | :-: |
| ![Assistant d'installation — Étapes 1 et 2](docs/images/assistant-etapes-1-2.png) | ![Assistant d'installation — Étapes 3 et 4](docs/images/assistant-etapes-3-4.png) |

1. **Étape 1 — Intelligence artificielle (OpenRouter en 1 clic) :**
   - Toucher **« Se connecter à OpenRouter en 1 clic »** : la page officielle d'OpenRouter s'ouvre
     dans le navigateur (flux **OAuth PKCE**), vous vous connectez, et la clé d'API revient toute
     seule dans Miam-Miam où elle est chiffrée dans le **Keystore Android** sans avoir à la copier.
   - Vous pouvez aussi coller manuellement une clé `sk-or-v1-…`.
2. **Étape 2 — Bases et voix hors ligne (sans PC) :**
   - Toucher **« Tout télécharger et installer automatiquement »** (Wi-Fi conseillé, ~420 Mo
     compressés). L'application télécharge les archives depuis GitHub, vérifie leur empreinte
     **SHA-256** et les décompresse dans son dossier :
     - **`kroko-fr.zip`** (52 Mo) → dictée vocale hors ligne sur le téléphone (~1 s de calcul) ;
     - **`off-base.db.zip`** (310 Mo compressés → 653 Mo) → 3,06 millions de produits Open Food
       Facts hors ligne (activée aussitôt sans redémarrer l'application) ;
     - **`vosk-model-fr.zip`** (40 Mo) → écoute hors ligne du mot-clé *« Miam-miam »* ;
     - **`piper-fr-upmc.zip`** (77 Mo, facultatif) → voix française locale Piper.
   - Comme c'est l'application elle-même qui crée ces fichiers, **les permissions Linux sont bonnes
     d'office** (aucun `adb push` ni `chmod` requis). Un bouton *« Choisir un .zip local »* permet
     aussi d'importer une archive déjà présente sur le téléphone.
3. **Étape 3 — Microphone et notifications :**
   - Deux boutons demandent directement l'accès au microphone (pour dicter le repas) et aux
     notifications (pour le suivi et l'écoute du mot-clé).
4. **Étape 4 — Saisie automatique CamAPS FX & Glooko (optionnel) :**
   - **Astuce Android 13+ (« Paramètres restreints ») :** sur Android 13 et plus, le système grise
     par défaut les services d'accessibilité d'un APK installé hors Play Store. L'assistant propose
     deux boutons numérotés dans l'ordre :
     1. **« 1. Ouvrir la fiche de l'appli (Paramètres restreints) »** → toucher le menu `⋮` en haut
        à droite → *« Autoriser les paramètres restreints »* ;
     2. **« 2. Ouvrir les réglages d'Accessibilité Android »** → activer **« Insertion repas CamAPS
        (Miam-Miam) »** et **« Export Glooko (Miam-Miam) »**.
   - **Diagnostic après mise à jour :** si Android laisse un service coché après une mise à jour
     d'APK mais cesse de le relier en arrière-plan, l'assistant le détecte (*« Coché dans Android
     mais décroché »*) et vous invite à le décocher puis le recocher.

<details>
<summary>Installation manuelle par câble USB (adb, pour les développeurs)</summary>

```powershell
adb install -r Miam-Miam-0.14.0-arm64.apk
```

Si vous copiez les dossiers à la main par `adb push` dans
`/sdcard/Android/data/com.najah.mealannouncer/files/`, ils appartiennent à l'utilisateur `shell` et
ne sont pas lisibles par l'application sans `chmod` :

```bash
adb shell 'cd /sdcard/Android/data/com.najah.mealannouncer/files && \
  find sherpa vosk-model-fr -type d -exec chmod 777 {} \; && \
  find sherpa vosk-model-fr -type f -exec chmod 666 {} \; && chmod 666 off-base.db'
```

</details>

---

## Ce qu'elle ne fait pas — et ne fera pas

Ces limites sont des garde-fous, pas des options :

- elle **ne calcule aucune dose d'insuline** et ne donne aucune recommandation de dose ;
- dans CamAPS FX, elle n'utilise que l'écran **« Ajouter un repas »**, qui *informe* la boucle
  fermée : ce n'est **pas** un bolus. Elle remplit la quantité et le type de repas, puis **vous
  laisse appuyer vous-même sur « Confirmer »** ; elle ne valide jamais à votre place ;
- elle ne pilote pas la pompe et ne modifie aucun réglage de l'algorithme ;
- elle **n'invente pas un chiffre en silence** : toute valeur estimée est marquée comme telle à
  l'écran (« glucides estimés », « poids d'une pièce estimé ») et le repas ne part pas tout seul
  vers CamAPS tant qu'un doute reste ;
- un aliment introuvable n'est **jamais compté zéro** : il est signalé ;
- aucun secret (clé d'IA, mot de passe Nightscout) n'est dans le dépôt, les préférences en clair
  ou les journaux : ils sont chiffrés par le **Keystore** Android.

---

## Un repas, du début à la fin

1. **Déclencher.** Dire *« Miam-miam »* (téléphone posé à côté de soi), toucher la tuile
   « Miam-miam » des réglages rapides, ou ouvrir l'appli et toucher **« Décrire mon repas à voix
   haute »**. On peut tout dire d'une traite (*« Miam-miam, je mange deux pains au chocolat »*) ou
   en deux temps (*« Miam-miam »* → *« Je t'écoute »*).
2. **Dicter.** La phrase est transcrite sur le téléphone (Kroko). L'enregistrement s'arrête seul
   après ~1,5 s de silence.
3. **Comprendre.** L'IA de conversation transforme la phrase en liste d'aliments, avec les
   quantités telles qu'elles ont été dites.
4. **Chercher.** Pour chaque aliment, l'application cherche une valeur réelle (voir
   [la section suivante](#doù-viennent-les-glucides)) et fait le calcul elle-même.
5. **Préciser.** S'il manque quelque chose, **une seule question** est posée : la quantité, la
   portion en photo (« 100, 200 ou 300 grammes ? »), « cuit ou cru ? »… On répond à la voix ou
   on touche l'écran.
6. **Relire.** Un « diététicien » (une IA d'une autre famille) relit le repas avec vos propres
   mots. S'il doute, il pose sa question ; sinon le repas est prêt.
7. **Photographier (si besoin).** Un emballage, un code-barres ou un tableau nutritionnel en
   photo remplacent une estimation par la vraie fiche. Une voix décrit ce que la photo montre.
8. **Annoncer.** *« J'ai compris : deux pains au chocolat, 41 grammes de glucides. Repas ou
   collation ? »*
9. **Saisir dans CamAPS FX.** L'application ouvre « Ajouter un repas », remplit la quantité,
   coche le type et vous laisse **confirmer**.
10. **Noter dans le Carnet Glooko.** Après votre confirmation, la description du repas est
    écrite dans le Carnet Glooko (les glucides y arrivent par la synchronisation de CamAPS).

---

## D'où viennent les glucides

L'ordre de recherche est pensé pour aller **du plus sûr au moins sûr** :

| Ordre | Source | Pour quoi | Où |
| :-: | :-- | :-- | :-- |
| 1 | **Photo** de l'emballage, du code-barres ou du tableau | le produit exact que vous avez en main | IA de vision, chiffres lus seulement |
| 2 | **Garde-manger personnel** (factures importées, favoris) | les produits que vous achetez vraiment | base Room du téléphone |
| 3 | **Banque de portions en images** | aliments sans marque : riz, pâtes, fruits, légumes… | 33 aliments pesés embarqués + vos paquets |
| 4 | **Open Food Facts hors ligne** | 3 065 819 produits du commerce | fichier `off-base.db` (653 Mo) sur le téléphone |
| 5 | **Open Food Facts en ligne** | un produit trop récent pour la base locale | Internet |
| 6 | **Estimation de l'IA** | un plat maison, une spécialité | marquée « estimée », photo proposée |

Quelques règles importantes :

- **Une marque dite passe devant** : « pains au chocolat Carrefour » rend la fiche Carrefour, pas
  les pains au chocolat les plus scannés de France. Sans marque dite, vos **enseignes habituelles**
  passent en tête (c'est un tri, jamais un filtre).
- **Cuit ou cru.** La banque chiffre le riz, les pâtes, la semoule… **cuits**. Si vous donnez un
  poids sans dire « cuit » ni « cru », l'application pose la question : 100 g de riz cru font
  environ 2,5 fois plus de glucides que 100 g de riz cuit.
- **Photo sans hallucination.** L'IA de vision lit la photo **sans connaître votre repas** (elle
  ne peut pas recopier un nom de la liste) ; tout nombre qui n'apparaît pas dans le texte qu'elle
  a lu est rejeté.
- **Factures prudentes.** Un article de facture n'est relié à une fiche que si les mots
  concordent, sans transformation cachée (« bananes » ne devient jamais « bananes séchées ») ; les
  fruits et légumes sans marque restent à la banque de portions.

---

## Les fonctions une par une

### Dictée et mot-clé « miam-miam »

- **Mot-clé hors ligne** (Vosk, grammaire restreinte, mots leurres) : 0 fausse alerte sur
  168 phrases de télévision ; une seconde écoute (Kroko) confirme avant de réveiller l'appli.
- **Dictée sur le téléphone** (Kroko, via sherpa-onnx) : environ 1 s de calcul pour 14 s de
  parole. En secours : Voxtral (via OpenRouter).
- **Arrêt automatique** au silence ; service au premier plan avec notification ; tuile de
  réglages rapides.
- « Miam-miam » est réservé au téléphone ; « Okay Nabu… » reste réservé à l'enceinte de la maison.

### La conversation du repas

- Mémoire de tout l'échange : on peut corriger (*« non, trois biscuits »*), ajouter, dire
  *« je ne sais pas »* ou *« annule »*.
- *« Ce n'est pas ce produit »* (à la voix ou par le bouton **« Pas ce produit »**) écarte la
  fiche pour tout le repas.
- Questions posées par l'application elle-même quand l'IA oublie : portion manquante, paliers en
  photo, cuit ou cru.
- Relecture par une IA d'une autre famille, qui voit la portion imprimée sur l'emballage.

### Portions en images

- Une **carte photo** montre à quoi ressemblent 100, 200 ou 300 g ; on choisit à la voix
  (« la moyenne », « la deuxième », « 200 grammes ») ou en touchant une puce.
- Banque embarquée de **33 aliments pesés** (Nutrition5k, Google, CC BY 4.0).
- **Import / export de vos propres paquets** (`.zip` avec `portions.json` et les photos) :
  Réglages → Portions en images. Format décrit dans [`docs/PORTIONS.md`](docs/PORTIONS.md) ;
  il garde aussi sucres, fibres, lipides, protéines et index glycémique.

### Photos

- **Assiette** (hors repas) : l'IA décrit ce qu'elle voit et propose les aliments.
- **Emballage ou code-barres** (hors repas ou en plein repas) : nom, marque, code-barres et poids
  d'une pièce sont lus, puis la fiche est cherchée dans Open Food Facts hors ligne. En plein repas,
  elle remplace l'estimation de la ligne correspondante, ou ajoute le produit.
- **Tableau nutritionnel** : les glucides pour 100 g imprimés remplacent une estimation.

### Mes enseignes & factures de courses

- **Enseignes habituelles** (Carrefour, E.Leclerc, Système U, Intermarché, Auchan, Lidl, Aldi,
  Casino / Monoprix / Franprix, Picard) : leurs marques propres (Carrefour Classic, Simpl, Marque
  Repère, U, Chabrior, Milbona…) passent en tête des résultats.
- **Import d'une facture ou d'un ticket** (PDF de drive, photo de ticket, texte), depuis le
  téléphone ou depuis **Google Drive** : chaque article alimentaire est relié une fois pour toutes
  à sa fiche Open Food Facts et rangé dans le **garde-manger personnel**. L'enseigne lue en
  en-tête est cochée d'office. Le résumé dit combien de produits ont été reconnus et combien de
  produits frais ont été laissés à la banque de portions.

### La voix

- **Voxtral** (Mistral, voix « Marie ») : la plus naturelle, demande une clé Mistral.
- **Piper upmc** : calculée sur le téléphone, hors ligne.
- **Voix Android** : toujours disponible, sert de repli si le réseau traîne.

### CamAPS FX et Carnet Glooko

- Deux **services d'accessibilité**, désactivés par défaut :
  - **« Insertion repas CamAPS (Miam-Miam) »** remplit « Ajouter un repas » et observe votre
    « Confirmer » ;
  - **« Export Glooko (Miam-Miam) »** écrit la description du repas dans le Carnet Glooko après
    la confirmation, et pilote l'export CSV de Glooko.
- Détails, ancres d'écran et contrat : [`docs/CAMAPS-REPAS.md`](docs/CAMAPS-REPAS.md),
  [`docs/GLOOKO-CARNET.md`](docs/GLOOKO-CARNET.md).

### Nightscout et import Glooko

- **Envoi des repas à Nightscout** (désactivé par défaut, mode simulation disponible) :
  WorkManager, réessais, jamais de doublon (identifiants UUIDv5).
- **Import des données Glooko** (CSV exporté par l'application Glooko) vers :
  - **Nightscout classique** (traitements v1/v3), ou
  - **Nightscout for CamAPS FX** (fork dédié : envoi du CSV brut à `/api/v1/camaps/imports`,
    aperçu puis validation).
- Détail colonne par colonne : [`docs/GLOOKO.md`](docs/GLOOKO.md).

### La voix de la maison

- Dicter son repas à une enceinte **Home Assistant Voice** (*« Okay Nabu, je mange… »*) : le
  calcul se fait sur le téléphone, les questions et l'annonce passent par l'enceinte.
- *« Okay Nabu, annonce 25 grammes, repas, et note Glooko »* saute le calcul.
- Conception : [`docs/VOIX-MAISON.md`](docs/VOIX-MAISON.md).

---

## Les réglages

| Section | Ce qu'on y règle |
| :-- | :-- |
| **Assistant d'installation simplifiée** | état des 4 étapes, téléchargement/mise à jour des modèles hors ligne, diagnostic d'accessibilité |
| **Intelligence artificielle** | connexion OpenRouter en 1 clic (OAuth PKCE) ou clé `sk-or-v1-…` (chiffrée), vérification |
| **Voix** | Voxtral, Piper ou voix Android ; clé Mistral pour Voxtral |
| **Mot-clé « miam-miam »** | écoute permanente, tuile de réglages rapides |
| **Portions en images** | importer / exporter des paquets, crédits de la banque |
| **Mes enseignes & factures de courses** | enseignes habituelles, import de facture (téléphone ou Drive), vider le garde-manger |
| **Enregistrement du repas** | insertion automatique dans CamAPS FX, Carnet Glooko (« Suivi diabétologue ») |
| **Nightscout** | adresse, secret ou jeton d'accès, test de connexion, simulation |
| **Glooko** | destination de l'import, synchronisation, réglages d'accessibilité |

---

## Données personnelles, réseau et coûts

Ce qui **reste sur le téléphone** : la dictée (Kroko), le mot-clé (Vosk), la voix Piper, la base
Open Food Facts, la banque de portions, le garde-manger, l'historique des repas, les clés.

Ce qui **part sur Internet**, et pourquoi :

| Vers | Quoi | Quand |
| :-- | :-- | :-- |
| **OpenRouter** (Google Gemini, Mistral) | le texte du repas, les photos prises, le contenu d'une facture importée | à chaque échange de la conversation, chaque photo, chaque import |
| **Mistral** (Voxtral) | le texte des annonces | seulement si la voix Voxtral est choisie |
| **Open Food Facts** | un nom de produit ou un code-barres | seulement si la base locale ne l'a pas |
| **Votre Nightscout** | repas et données Glooko | seulement si c'est activé |

**Coût indicatif** (tarifs OpenRouter, novembre 2026) : environ **0,15 centime de dollar par échange**
(conversation + relecture) et **0,05 centime de dollar par photo** ; un repas complet coûte en général
moins d'un centime de dollar.

**Modèles utilisés** (choisis sur un banc de 150 assiettes pesées, décision 48) :

| Rôle | Modèle |
| :-- | :-- |
| conversation, photos, estimations, factures | `google/gemini-3.1-flash-lite` |
| relecture du repas, portions | `mistralai/mistral-medium-3.1` |
| dictée de secours | `mistralai/voxtral-small-24b-2507` |

---

## Suivre ce qui se passe (journaux)

Tout est tracé dans `logcat`, sans aucune donnée secrète :

```bash
adb logcat -T 1 -v time -s MealAnnouncerMeal MealAnnouncerLocal MealAnnouncerTiming \
  MealAnnouncerCamaps MealAnnouncerCarnet MealAnnouncerHome
```

| Étiquette | Contenu |
| :-- | :-- |
| `MealAnnouncerMeal` | chaque ligne du repas, sa source (`via` photo, facture, banque, recherche, estimation), le total, l'envoi à CamAPS |
| `MealAnnouncerLocal` | dictée locale : durée de calcul, durée d'audio, texte |
| `MealAnnouncerTiming` | durée de chaque appel d'IA, jetons consommés |
| `MealAnnouncerCamaps` | ce que l'automate voit dans CamAPS, issue (« CONFIRMED »…) |
| `MealAnnouncerCarnet` | chaque étape de l'écriture dans le Carnet Glooko |
| `MealAnnouncerHome` | voix de la maison (enceinte) |

> Le journal Android d'un téléphone est souvent très bavard et ne garde que quelques minutes :
> lancer la commande **avant** l'essai, pas après.

---

## Pour les développeurs

### Compiler

```powershell
.\gradlew.bat test assembleDebug --console=plain     # tous les tests + APK de mise au point
.\gradlew.bat :feature:assistant:testDebugUnitTest   # tests d'un module
.\build.ps1                                          # APK release signé + empreinte SHA-256
```

- Sur un PC à la mémoire comptée, ajouter `"-Pkotlin.compiler.execution.strategy=in-process"`
  (sinon le démon Kotlin peut être tué).
- La bibliothèque native **sherpa-onnx 1.13.8** (`app/libs/*.aar`, 50 Mo) n'est pas versionnée :
  la retélécharger comme indiqué dans [`docs/VOIX-LOCALE.md`](docs/VOIX-LOCALE.md).
- `testDebugUnitTest` ne lance que les modules Android ; le domaine (`core:model`, module
  Kotlin/JVM) ne tourne qu'avec `test`.

### Signature

L'APK release est signé avec `mealannouncer.jks` (alias `mealannouncer`) ; les mots de passe sont
dans `keystore.properties`. Ces deux fichiers ne sont **jamais** versionnés. Perdre cette clé
obligerait à désinstaller l'application avant toute mise à jour.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

### Pile technique

Kotlin 2.3, Jetpack Compose (BOM 2026.03), Hilt, Room, WorkManager, DataStore ; AGP 9.0,
Gradle 9.1 ; `compileSdk 36`, `targetSdk 35`, `minSdk 29` ; APK limité à **arm64-v8a**. Dépôts
Maven restreints à Google et Maven Central (`FAIL_ON_PROJECT_REPOS`).

### Structure

```
app/                       assemblage, thème aquarelle, accueil, dictée locale (Kroko), mot-clé
core/model/                domaine pur (Kotlin/JVM, aucune dépendance Android) + tests
core/common/               utilitaires partagés
core/database/             Room : produits, repas, lignes
core/datastore/            réglages et secrets (Keystore) : IA, voix, enseignes, garde-manger…
feature/assistant/         conversation du repas, recherche, relecture, photos, portions en images
feature/voice/             annonces : Voxtral, Piper, voix Android
feature/meal/              accueil, saisie manuelle
feature/food/              recherche et favoris de produits
feature/confirmation/      confirmation et envoi
feature/history/           historique des repas
feature/settings/          tous les réglages
data/mistral/              appels d'IA (OpenRouter) : conversation, vision, emballages, factures
data/openfoodfacts/        base hors ligne, API, garde-fou des factures (ReceiptMatch)
data/voxtral/              voix Voxtral
data/nightscout/           client Nightscout (v1 et v3, JWT)
data/homeassistant/        voix de la maison
integration/camaps/        saisie dans CamAPS FX (accessibilité)
integration/glooko/        CSV Glooko → Nightscout
integration/glookoexport/  pilotage de Glooko : export CSV, Carnet
```

### Documents

| Document | Sujet |
| :-- | :-- |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | les 49 décisions et leurs raisons |
| [`docs/DIALOGUE-REPAS.md`](docs/DIALOGUE-REPAS.md) | la conversation du repas |
| [`docs/PORTIONS.md`](docs/PORTIONS.md) | banque de portions en images, format des paquets |
| [`docs/OFF-LOCAL.md`](docs/OFF-LOCAL.md) | fabrication de la base Open Food Facts hors ligne |
| [`docs/PHOTO-ASSIETTE.md`](docs/PHOTO-ASSIETTE.md) | lecture des photos |
| [`docs/MIAM-MIAM.md`](docs/MIAM-MIAM.md) | mot-clé « miam-miam » |
| [`docs/VOIX-LOCALE.md`](docs/VOIX-LOCALE.md) | dictée Kroko et voix Piper |
| [`docs/VOXTRAL.md`](docs/VOXTRAL.md) | voix Voxtral |
| [`docs/CAMAPS-REPAS.md`](docs/CAMAPS-REPAS.md) | saisie dans CamAPS FX |
| [`docs/GLOOKO.md`](docs/GLOOKO.md), [`docs/GLOOKO-CARNET.md`](docs/GLOOKO-CARNET.md) | import Glooko, Carnet |
| [`docs/VOIX-MAISON.md`](docs/VOIX-MAISON.md) | voix de la maison (Home Assistant) |
| [`docs/blueprint-meal-announcer.md`](docs/blueprint-meal-announcer.md) | cahier des charges d'origine |

### Choix notables

- **Le modèle ne fournit jamais un nombre de glucides** : il fournit des noms, des quantités
  dites et au plus une estimation de poids ; le calcul est fait par l'application.
- **Toute modification d'une consigne d'IA demande un essai réel** : les tests à faux modèle ne
  voient pas un changement de comportement du modèle.
- **Énumérations stockées par leur nom**, jamais par leur ordinal.
- **Le total ignore les aliments non chiffrés** et les signale.

---

## Avertissement médical

Cette application aide à **compter et saisir des glucides**. Elle ne calcule pas d'insuline, ne
remplace pas un avis médical, et ses valeurs — même vérifiées par deux IA — peuvent être fausses.
**Vérifiez toujours le total avant de confirmer dans CamAPS FX.**

Ce projet n'est affilié ni à CamDiab (CamAPS FX), ni à Glooko, ni à Open Food Facts, ni à
Nightscout.

## Licences et crédits

- **Code** : usage personnel, pas encore de licence publiée.
- **Banque de portions** : photos et pesées du jeu de données **Nutrition5k** (Google Research),
  licence **CC BY 4.0**.
- **Open Food Facts** : base sous licence **ODbL**, images CC BY-SA.
- **Police Dancing Script** : licence **SIL Open Font License** ([`docs/licences/`](docs/licences/)).
- **Vosk** et **sherpa-onnx** : licence Apache 2.0.
- **Kroko** (dictée) et **Piper upmc** (voix) : licences de leurs modèles, **à vérifier avant
  toute diffusion publique** (voir [`docs/VOIX-LOCALE.md`](docs/VOIX-LOCALE.md)).
