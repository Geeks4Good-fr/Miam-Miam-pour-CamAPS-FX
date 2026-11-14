# Le dialogue du repas — conception (27/10/2026)

## Pourquoi

Jusqu'à 0.9.0, l'IA était appelée comme une fonction : une phrase → un JSON, sans mémoire, sans
droit de douter. Les seules questions possibles étaient écrites dans le code (portion, type de
repas). Résultat : quand elle hésitait, elle tranchait — c'est ainsi que la règle « un/une vaut 1 »
(ajoutée le 26/10 au soir) a court-circuité le premier dialogue de portion et fait finir
« un lait chocolaté dans une tasse » à 0 g.

Ce n'était pas une limite de Mistral (`mistral-small-latest` gère le multi-tours) : c'était
l'architecture. Décision de l'utilisateur (27/10) : construire un vrai dialogue, directement (option B).

## Principe

**L'IA comprend et fait préciser ; l'application cherche et calcule.**

Un échange (`MealConversationEngine.turn`) :

1. La parole de l'utilisateur part chez Mistral **avec toute l'histoire** (`JsonAsk.history`).
   Premier tour « Repas décrit : « … » », ensuite « Réponse : « … » ».
2. Le modèle rend la liste **complète** des aliments (ids stables), la quantité **telle qu'elle est
   dite**, et au plus une question (`MealConversation`).
3. L'application cherche chaque valeur (`MealCarbPipeline.lookup` : Open Food Facts → web →
   estimation signalée), avec un cache par nom pour la durée de la conversation, et calcule chaque
   ligne (`AssistantLine.carbResult`).
4. S'il reste une ligne non chiffrée et que le modèle n'a pas déjà posé de question, l'application
   lui envoie un **BILAN** (fiche trouvée, portion de l'étiquette, ce qui manque, total) ; il choisit
   alors **une** question, sur **un** aliment.

## Garde-fous

- Le modèle ne donne **jamais** de glucides. Il peut seulement estimer un **poids** (`grams`) pour
  une mesure de ménage dite (tasse, bol, assiette…) ou quand l'utilisateur dit « je ne sais pas » ;
  l'hypothèse (`assumption`) est affichée sous la ligne (`portionNote`). Bornes 1–3000 g.
- Une quantité dite que l'étiquette sait chiffrer (« 2 biscuits » avec une portion connue, des
  grammes) passe **avant** le poids estimé.
- `ViewModel.runConversation` : arrêt après 2 silences, après 3 réponses incomprises (proposition
  de photo), sur « annule », ou après 10 échanges. Les lignes restent à l'écran, modifiables.
- Fin normale : si tout est chiffré et que l'insertion automatique CamAPS est réglée, la
  confirmation CamAPS existante prend le relais (elle annonce le repas **une seule fois** et demande
  le type) ; sinon l'assistant dit le repas et le total. Rien n'est écrit sans la réponse vocale, et
  l'automate s'arrête toujours **avant** « Confirmer ».

## Chiffrer à la pièce (demande de l'utilisateur, 27/10)

Cas d'origine : « deux pains au chocolat Carrefour », Open Food Facts en panne (503) → 0 g.
Ordre attendu, désormais codé :

1. Open Food Facts (local puis en ligne).
2. À défaut, **fiche web de l'emballage** (`WebCarbResearcher`) : `carbs_per_100g`, et pour un
   produit à la pièce `carbs_per_unit` (prioritaire), sinon `unit_weight_g` — écrit, ou déduit du
   paquet `package_weight_g ÷ units_per_package` — avec `unit_detail` affiché sous la ligne.
3. Produit Open Food Facts qui ne sait pas chiffrer une pièce → même fiche web « par pièce »
   (`MealCarbPipeline.unitInfo`), avant toute estimation.
4. Dernier recours : poids estimé par le modèle (`grams`, hypothèse affichée).
5. Une ligne encore à 0 g → l'application pose elle-même la question de portion (une fois par ligne).

## Photo du produit (vérification d'un coup d'œil)

Chaque ligne affiche la photo du produit chiffré **seulement si elle le désigne sûrement**
(`MealCarbPipeline.imageFor`) :

- fiche Open Food Facts (`image_front_small_url`), ou photo demandée **par code-barres** pour un
  produit de la base hors ligne ;
- valeur du web : image de la **page même** de la fiche (même site, HTTPS), ou photo Open Food Facts
  du code-barres lu sur la page ;
- estimation moyenne : **jamais** de photo.

Sinon aucune image — ni cadre vide ni image de remplacement. La vignette n'apparaît qu'une fois
réellement chargée (HTTPS, type image/*, 3 Mo max), avec son origine écrite dessous.

## Ce qui reste de l'ancien chemin

- `MealCarbPipeline.analyse` + `MealExtraction` : conservés (tests), plus appelés par l'écran. La
  règle « un/une vaut 1 » y est retirée.
- Premier dialogue de portion : toujours accessible par la carte « Préciser les portions » quand une
  ligne reste sans quantité à la fin.
- Photo d'assiette : inchangée (`resolveLines`).

## À éprouver sur le Hammer

« un lait chocolaté dans une tasse », « des biscuits Prince » (question attendue), un ajout en cours
de route (« et aussi un yaourt »), une correction (« non, pas de pain »), « je ne sais pas »,
« annule ».
