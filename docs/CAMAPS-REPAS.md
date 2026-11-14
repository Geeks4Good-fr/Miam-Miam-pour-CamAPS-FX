# Contrat M4 — enregistrer un repas (CamAPS FX, Glooko, Nightscout)

**État :** contrat figé le 26/10/2026 avec l'utilisateur, à partir d'un relevé réel de son téléphone
(Hammer Iron 6 5G, Android 15, écran 720×1612, interfaces en français). **Rien de ce document
n'est encore implémenté** : il décrit ce qui doit l'être, et ce qui ne doit jamais l'être.

Le relevé Glooko a son propre document : [`GLOOKO-CARNET.md`](GLOOKO-CARNET.md).

---

## 0. La ligne rouge (médical)

1. **« Ajouter un repas » dans CamAPS n'est PAS un calculateur de bolus.** Cette étape **n'injecte
   aucune insuline** : elle donne seulement une *information* à l'algorithme de boucle fermée (auto
   mode) — « si la glycémie monte, c'est un repas ». L'algorithme gère même sans ; l'info l'aide à
   comprendre la cinétique. Le dosage d'insuline vit dans un parcours **séparé**, le **Calculateur
   de bolus**, que **l'automate ne touche jamais**.
2. L'automate **n'agit que sur une confirmation vocale explicite** de l'utilisateur (« oui » / « ok » /
   « je confirme »). Toute autre réponse, un doute, un silence → **il n'appuie pas**.
3. L'automate remplit le champ des glucides, coche le type de repas **que l'utilisateur a choisi
   vocalement**, puis appuie sur **« Continuer »** — et **s'arrête là**. Il ne va jamais sur un
   écran qui dose ou administre de l'insuline.
4. Si l'écran n'est pas **exactement** celui attendu (titre + champ reconnus), l'automate
   **abandonne** avec un message clair et laisse CamAPS tel quel. **Jamais de saisie partielle
   silencieuse, jamais de geste « au cas où ».**
5. Tout est **désactivé par défaut** : il faut activer le réglage *et* le service d'accessibilité
   Android à la main.

---

## 1. Le produit : vite d'abord, détaillé seulement si besoin

Un diabétique veut manger vite, avec le moins de charge mentale possible — pas décrire chaque
collation. Le détail du repas n'est utile qu'au diabétologue, surtout les ~15 jours avant un
rendez-vous. D'où **deux régimes** :

| Régime | Réglage | Ce qui part à la confirmation |
| :-- | :-- | :-- |
| **Rapide** (défaut) | « Suivi diabétologue » **désactivé** | glucides → CamAPS *(et, si activé, glucides → Nightscout)*. Pas de description, pas de photo, dialogue minimal. |
| **Suivi diabétologue** | réglage **activé** (p. ex. 15 j avant un RDV, option : date de fin) | en plus : **description** du repas → Carnet Glooko (+ photo), **note** = description → Nightscout. |

Chaque cible — **CamAPS**, **Glooko**, **Nightscout** — reste **désactivable individuellement**
dans les réglages, par-dessus le régime choisi.

---

## 2. Le dialogue vocal d'une confirmation

Au moment où l'assistant connaît le repas (aliments + total de glucides + description composée) :

1. **« X grammes de glucides. Quel type : repas ou collation, traitement de l'hypoglycémie, ou
   repas à absorption lente ? »** → l'utilisateur répond **vocalement** ; la transcription (STT) est
   appariée à l'un des trois types. Réponse non reconnue → on redemande une fois, puis on abandonne.
2. **« X grammes, [type]. Tu confirmes ? »** → l'utilisateur dit **« oui » / « ok » / « je confirme »**.
3. Sur confirmation seulement, l'orchestration exécute les cibles **activées**, le médical en
   dernier (§3).
4. **« Repas enregistré. »** (l'assistant nomme les cibles réellement écrites).

Le texte exact du repas ne contient **que** les aliments et leurs portions — **jamais** de
glycémie ni d'insuline.

---

## 3. L'orchestration (ordre, modes, entraînement) — RÉVISÉ le 26/10/2026

**Ordre, dans tous les cas : CamAPS FX d'abord, puis Glooko Carnet.** (Révision du 26/10 ; le code
actuel fait encore l'inverse — à aligner.) La note Nightscout est **gelée** (fork, voir §6).

**Deux modes au choix dans les réglages :**

- **① Confirmation manuelle (défaut, le seul prudent au départ).** Dicter → toucher « Écrire dans
  CamAPS » → l'assistant demande le type + « tu confirmes ? » à la voix → l'automate remplit +
  coche le type + **« Continuer »**, puis **s'ARRÊTE** sur la boîte **« Confirmer »** (§4) :
  **c'est l'utilisateur qui presse « Confirmer »**. L'automate **attend** que la boîte se ferme (l'utilisateur a
  pressé Confirmer ou Annulé), **puis** écrit le Carnet Glooko. Ainsi CamAPS d'abord, sans que
  Glooko vole l'écran.

- **② Mains-libres (ultra-court, à débloquer).** Dès l'analyse, l'assistant **lit tout seul** la
  liste + les glucides et demande confirmation (sans bouton) → confirmation vocale → l'automate
  **entre dans CamAPS**, **demande le type** (comme en ①), **résume** de nouveau repas + glucides et
  redemande confirmation **à la voix** → sur ce « je confirme », l'automate remplit + « Continuer »
  + **presse lui-même « Confirmer »**. Le « Confirmer » automatique n'est permis **que** juste après
  cette confirmation vocale faite dans CamAPS. Puis Carnet Glooko.

**Verrou d'entraînement (décision de l'utilisateur) :** le mode ② reste **verrouillé tant que 20 repas
n'ont pas été enregistrés en mode ①**. Les 20 premiers repas forcent le mode manuel ; au-delà,
l'utilisateur peut débloquer le mode mains-libres. (Compteur à persister, p. ex. dans
`MealRecordConfig`.)

Chaque cible rend un résultat propre ; **l'échec de l'une n'empêche pas les autres**, et aucune ne
« réessaie » en boucle.

> **Non encore implémenté** (décision du 26/10 : « nous le ferons plus tard »). Le code installé
> (0.9.0) fait : Glooko **avant** CamAPS, mode ① sans attente du « Confirmer ». À refondre selon
> cette section.

---

## 4. Le relevé CamAPS « Ajouter un repas » (ancres)

Paquet : `com.camdiab.fx_alert.mgdl` (voir `CamapsPackages.CANDIDATES`). Relevé le 26/10/2026.

**Entrée depuis l'accueil** : menu latéral, `text="Ajouter un repas"`.

**Écran repas** — activité `…/a.setHideOnContentScrollEnabled`, titre `text="Ajouter un repas"` :

| Rôle | Ancre | Action de l'automate |
| :-- | :-- | :-- |
| Champ des glucides | **EditText `id=2131297331`** (placeholder « Quantité », libellé « g de glucides ») | `ACTION_SET_TEXT` = le nombre exact |
| Type « Repas ou collation » | `RadioButton id=2131297017` | coché si l'utilisateur a dit « repas / collation » |
| Type « Traitement de l'hypoglycémie » | `RadioButton id=2131296872` | coché si l'utilisateur a dit « hypo… » |
| Type « Repas à absorption lente » | `RadioButton id=2131297326` | coché si l'utilisateur a dit « absorption lente » |
| **Frontière** | **« Continuer »** `id=2131297248` (`desc="Suivant"`) | appuyé **une fois**, sur confirmation, **dernier geste** |

**« Continuer » peut ouvrir une boîte « Confirmer » — relevé important du 26/10 :**
- avec **1 g**, « Continuer » enregistrait l'annonce et revenait direct à l'accueil (pas de boîte) ;
- avec **28 g** (« Repas »), « Continuer » ouvre une boîte **« Repas — 28 g de glucides —
  Annuler / Confirmer »**. Ce « Confirmer » finalise l'annonce de repas (information à la boucle,
  **pas un bolus** : aucune unité d'insuline affichée).

En **mode ①**, l'automate **presse « Continuer » puis s'ARRÊTE** sur cette boîte (il ne touche
jamais « Confirmer » ; c'est l'utilisateur qui l'appuie). En **mode ②** seulement, l'automate presse
« Confirmer » lui-même, et uniquement juste après une confirmation vocale faite dans CamAPS (§3).

**L'automate refuse d'agir** si le titre ou l'EditText (champ « Quantité ») ne sont pas trouvés, ou
si l'arbre appartient au **Calculateur de bolus** (jamais notre cible).

## 4 ter. Éprouvé sur l'appareil le 26/10/2026 (0.9.0)

Validé en vrai (l'utilisateur présent, mode ①, phrase « deux biscuits Prince tout choco » → 28 g) :
- dialogue vocal **type → « je confirme »** : fonctionne ;
- navigation CamAPS : après correctif, l'automate **ouvre le tiroir de menu** (fermé par défaut),
  atteint « Ajouter un repas », **remplit « 28 »**, coche **« Repas »**, presse **« Continuer »** ;
- **« Continuer » ouvre la boîte « Confirmer » et l'automate s'arrête là** — la **ligne rouge a
  tenu**. L'utilisateur a **annulé** (c'était un test).
- Correctifs du jour : CamAPS n'ouvrait pas le tiroir ; Glooko ne dépassait pas le splash → boucles
  de réessai ajoutées (`reachMealScreen`, `reachAddRecord`). CamAPS **corrigé** ; **l'écriture du
  Carnet Glooko échoue encore** (aucune entrée 28 g après l'essai) → à déboguer lors de la refonte.
- Pièges : une **réinstallation désactive le service d'accessibilité CamAPS** (à réactiver à la
  main) ; `settings put secure enabled_accessibility_services` est **refusé en auto-mode**
  (« Security Weaken ») → passer par l'UI d'Android.

---

## 5. Architecture (à implémenter, patron = Glooko)

- **`CamapsAccessibilityService`** (module `:integration:camaps`) — calque de
  `GlookoAccessibilityService` : il n'agit pas de lui-même, il expose seulement l'arbre de l'écran
  courant. Déclaré dans le manifeste avec `BIND_ACCESSIBILITY_SERVICE` + `@xml/…`, `exported=true`.
- **`CamapsScreen`** (décision **pure**, testable JVM sans appareil) — à partir d'une description
  d'arbre factice : *bon écran repas* → `Fill(carbs, radioId)` ; *écran inconnu / bolus* → `Refuse`.
  C'est l'équivalent des règles de `GlookoMealTriggerTest`, isolé de `AccessibilityNodeInfo`.
- **`AccessibilityCamapsGateway : CamapsMealGateway`** — ouvre « Ajouter un repas », applique la
  décision via le service, rend `CamapsWriteResult.Entered` / `.Failed`. **Ne valide jamais.**
- **`CamapsModule`** — relie `AccessibilityCamapsGateway` **derrière un réglage désactivé par
  défaut** ; sinon `ManualCamapsGateway` (le filet actuel) reste branché.
- **Réglages** (`:feature:settings`) : interrupteur « Insertion automatique dans CamAPS »
  (+ lien vers les réglages d'accessibilité Android), interrupteur « Suivi diabétologue »
  (+ option date de fin), interrupteurs par cible.

**Tests unitaires attendus** (JVM, sans réseau ni appareil) : décision `CamapsScreen` (remplir /
refuser), appariement voix→type, reconnaissance de la confirmation (« oui/ok/je confirme » vs le
reste), et l'orchestration (une cible en échec n'empêche pas les autres ; cibles désactivées
ignorées).

---

## 6. Note Nightscout — décision et gel (26/10/2026)

**Constat en lisant le code :** les glucides saisis dans CamAPS **arrivent déjà** dans Nightscout
par la synchro Glooko→NS (`GlookoToNightscoutMapper`, traitement « Carb Correction »,
`enteredBy=Glooko-CamAPS`, dédup par `identifier` **contre lui-même** seulement). Une écriture
directe de glucides créerait donc un **doublon**. Le vrai manque dans NS est la **description**,
que ni CamAPS ni la synchro ne portent.

**Décision de l'utilisateur :** **option 2** — écrire un traitement « Carbs » direct (glucides +
description) et **adapter l'import Glooko** pour qu'il ne réimporte pas ces glucides-là.

**Gel :** la couche Nightscout est **en cours de réécriture** (fork **NightscoutCamAPS**, base de
test en cours d'installation sur le PC). La note NS est donc **non implémentée pour l'instant** :
l'interrupteur « note Nightscout » existe mais reste inerte. `writeSuiviTargets` dans le ViewModel
porte l'emplacement exact où la brancher quand l'API NS sera figée. **Ne pas écrire l'anti-doublon
tant que la réécriture n'est pas stabilisée** — il serait jeté.

---

## 7. Ce qu'il ne faut jamais faire

Remplir puis **valider un bolus** à la place de l'utilisateur ; toucher le **Calculateur de bolus** ;
appuyer sur « Continuer » **sans** confirmation vocale ; écrire un type de repas que l'utilisateur n'a pas
choisi ; inventer une valeur ; laisser une saisie partielle sans le dire. Ces lignes sont la
différence entre un outil et un risque.
