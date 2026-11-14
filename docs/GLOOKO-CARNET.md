# Contrat — écrire un repas dans le Carnet Glooko

**État :** contrat figé le 26/10/2026 à partir d'un relevé réel (Hammer, Android 15, 720×1612,
Glooko `com.diabnext.diabnext`, interface en français). **Pas encore implémenté.**

Complément de [`CAMAPS-REPAS.md`](CAMAPS-REPAS.md) : ici on **écrit** dans Glooko (le Carnet), alors
que `glookoexport` ne faisait jusque-là que **lire** l'export CSV. Les deux réutilisent le **même**
service d'accessibilité, `GlookoAccessibilityService` — aucun nouveau service à déclarer.

---

## 0. Quand et pourquoi

Le Carnet détaillé n'est utile qu'au diabétologue, surtout les ~15 jours avant un rendez-vous.
Il est donc écrit **uniquement** quand le réglage **« Suivi diabétologue »** est activé (défaut :
désactivé). En mode rapide, on n'y touche pas. Voir `CAMAPS-REPAS.md` § 1.

Écrire une entrée de Carnet est **sans conséquence médicale** (c'est un journal, aucune insuline) :
l'automate peut donc remplir **et** enregistrer. Il ne touche en revanche **jamais** les champs
Insuline de l'écran.

---

## 1. Le relevé (ancres)

**Entrée** : onglet « Suivi » → bouton **« Ajouter des données »** `id=btnAdd` → ouvre
`glooko.xt.pages.main.add_record.AddRecordActivity`.

**Écran `AddRecordActivity`** — sections dépliables. Seule la section **« Repas »** nous concerne :

| Rôle | Ancre | Action |
| :-- | :-- | :-- |
| Déplier la section Repas | `btnOpenCloseMeal` (`text="Repas"`) | cliquer si repliée |
| **Description du repas** | **EditText `id=editMealDescription`** (placeholder « Description... », libellé « Détails de votre repas ») | `ACTION_SET_TEXT` = la description |
| Glucides | **EditText `id=editCarbs`** (unité « g », libellé « Glucides ») | `ACTION_SET_TEXT` = le nombre |
| Photo du plat | `btnAddPhoto` (dans `recyclerViewPhotos`) | joint la photo de l'assistant (§3) |
| Moment : Petit-déj / Déjeuner / Dîner / En-cas | `btnBreakfast` / `btnLunch` / `btnDinner` / `btnSnack` | coché selon l'heure (§2) |
| **Enregistrer** | **`btnSave`** (`text="Enregistrer"`) | appuyé en dernier |

**Jamais touchés** : `editInsulinFast` / `editInsulinSlow` / `editInsulinCorrective` (section
Insuline), `editGlucose` (glycémie), `editActivity` / `editWeight`, `editNote` (sauf besoin futur).

L'automate **refuse** si l'activité n'est pas `AddRecordActivity` ou si `editMealDescription`
/ `editCarbs` sont introuvables.

---

## 2. Le moment du repas (auto d'après l'heure)

Choisi **automatiquement** d'après l'heure locale, sans geste de plus (décision du 26/10) :

- Petit-déjeuner : 05 h 00 – 10 h 59
- Déjeuner : 11 h 00 – 14 h 59
- Dîner : 18 h 00 – 22 h 59
- En-cas : le reste

Les bornes sont des constantes nommées, ajustables. Si aucun bouton de moment n'est trouvé, on
enregistre quand même l'entrée (le moment est un confort, pas une obligation).

---

## 3. La photo du plat

Décision du 26/10 : quand l'assistant a pris une photo d'assiette (fonction 0.8.0), elle est
**rattachée** à l'entrée via `btnAddPhoto`. Ce bouton ouvre le sélecteur/appareil de Glooko :
l'automatisation du sélecteur est plus lourde que la saisie de texte (elle sort de l'app Glooko),
donc elle est traitée **après** le texte et son échec **n'empêche pas** l'enregistrement de la
description — une entrée avec texte sans photo vaut mieux que pas d'entrée.

---

## 4. Architecture (patron = `GlookoAutomation`)

- Étendre `:integration:glookoexport` avec un flux **`addCarnetEntry(description, carbs, moment, photo?)`**,
  sur le modèle de `triggerExport` : spec de libellés/ids, décision libellé par libellé, abandon
  propre sur ancre manquante, aucun champ inventé.
- Réutiliser `GlookoAccessibilityService` (déjà déclaré) et les primitives `GlookoUi`
  (`find`, `clickAny`, `click`, défilement). Ajouter un helper `setText(id, value)` via
  `ACTION_SET_TEXT`.
- **Tests unitaires** (JVM) : choix du moment selon l'heure (bornes), et la **décision** sur un
  arbre factice (bon écran → remplir+enregistrer ; écran inconnu → refuser), comme
  `GlookoMealTriggerTest`.

---

## 4 bis. État et point à vérifier sur l'appareil

Implémenté le 26/10/2026 : `GlookoCarnet.addEntry(description, carbs, moment)` (ce module) +
orchestration dans `MealAssistantViewModel.writeSuiviTargets` (sous le mode « Suivi diabétologue »,
avant l'écriture CamAPS). `GlookoCarnetMoment.forHour` choisit le moment. Compile, tests verts.
**Non éprouvé sur l'appareil** (demande les gestes de l'utilisateur : services d'accessibilité Glooko +
CamAPS allumés, mode suivi activé, un repas d'essai).

**À vérifier sur l'appareil :** les glucides de CamAPS arrivent déjà dans Glooko (le Carnet affiche
« N g glucides » par repas). Écrire **aussi** les glucides dans l'entrée du Carnet pourrait les
faire apparaître **deux fois** côté Glooko. Si c'est le cas, n'écrire que la **description** dans le
Carnet (laisser `editCarbs` vide), les glucides restant portés par le chemin CamAPS→Glooko. À
trancher au premier essai réel.

## 5. Ce qu'il ne faut jamais faire

Toucher un champ **Insuline** ou **Glycémie** ; enregistrer une entrée quand « Suivi diabétologue »
est désactivé ; inventer une description ou un glucide absent ; laisser une saisie partielle sans
le dire.
