# Import Glooko → Nightscout

Ce document décrit ce que l'import fait exactement, ce qu'il produit sur un export réel, et
comment le vérifier sans rien écrire.

## 1. La chaîne

```
Glooko (téléphone)                        Meal Announcer                    Nightscout
─────────────────                         ──────────────                    ──────────
écran piloté ──► feuille de partage ──►   GlookoParser  ──►  Mapper  ──►   API v3 (token)
                 (aucun fichier)          GlookoShareActivity  31 colonnes  ou v1 (clé)
                                                                             voir § 8
```

Le CSV ne transite par aucun fichier : Glooko l'extrait sur son serveur puis l'offre à la
feuille de partage d'Android, où `GlookoShareActivity` le reçoit. Un partage manuel depuis
Glooko — ou depuis l'export du portail web — emprunte exactement le même chemin.

**Ce qui déclenche un passage.**

| Déclencheur | Quand | Pourquoi |
| :-- | :-- | :-- |
| Repas annoncé | 3 min après l'enregistrement du repas | le déclencheur normal : Glooko ne publie que toutes les quelques heures, et un repas est le moment où l'on veut son contexte |
| Filet de sécurité | toutes les 6, 12 ou 24 h — 24 h par défaut | rattrape les journées sans repas annoncé, où Glooko ne serait jamais interrogé |
| « Synchroniser maintenant » | à la demande | mise au point et rattrapage |

Deux repas rapprochés ne produisent qu'un export : trente minutes séparent deux
déclenchements, et un export manuel compte comme les autres. Le fichier couvrant sept jours,
un passage manqué ne perd aucune donnée — il retarde seulement le prochain.

**Le prix du pilotage.** L'automatisation passe par l'écran : Glooko s'affiche réellement une
quinzaine de secondes, et le téléphone doit être allumé et déverrouillé à ce moment-là. Il
n'existe pas de canal silencieux : Glooko ne publie aucune API patient, et son activité
d'export interne n'est pas exportée (`SecurityException` en adb). D'où le délai de trois
minutes — ne pas voler l'écran pendant la saisie du bolus — et la cadence basse.

## 2. Ce que devient chaque colonne

| Colonne du CSV | Devient | Remarque |
| :-- | :-- | :-- |
| `Blood glucose` (3) | document `sgv` | une valeur par horodatage ; le CSV en écrit 2 à 3 par minute |
| `Bolus` (4) + `Bolus Type` (14) | `Meal Bolus`, `Correction Bolus` ou `Combo Bolus` | bolus normal + bolus calculé de la même minute = **un** document |
| `Carbs` (19) | glucides du bolus, ou `Carb Correction` si aucun bolus | jamais additionnés deux fois |
| `Basal` (10) | `Temp Basal` (débit absolu) | une valeur par minute, la dernière gagne |
| `Event` (17) | `Note`, `Announcement`, `Insulin Change`, ou rien | voir le tableau ci-dessous |
| `Settings` (18) | profil `Glooko-CamAPS` | basal, ratio, sensibilité, cibles, DIA, unités, fuseau |
| 15, 16, 28 | — | ne contiennent que `false` dans les exports réels |

Détail des évènements :

| Libellé Glooko | Devient |
| :-- | :-- |
| `pumpMode - …`, `Closed loop`, `Liberty`, `Pining` | `Note` « CamAPS <mode> », avec la durée |
| `pumpSettingsOverride - boost` / `- easeOff` | `Note` « CamAPS Boost » / « CamAPS Ease-off », avec la durée |
| `ReservoirChange` | `Insulin Change` |
| `info - Total daily dose` | `Note` « CamAPS : dose totale quotidienne » |
| `Alerte de sécurité - …` | `Announcement` (l'alarme doit se voir dans la chronologie) |
| `pump settings` | rien : c'est le **profil** |
| `cgm settings` | rien : réglages du capteur, hors périmètre |

## 3. Résultat attendu sur l'export réel

Mesuré sur `EXPORT_GLOOKO.CSV` (17/10 → 24/10/2026, 4 807 lignes de données, 12 blocs de
réglages), sans limite de fenêtre, glycémies activées :

| Famille | Documents |
| :-- | --: |
| Glycémies (`sgv`) | 2 016 |
| Repas — dont bolus / glucides seuls | 42 — 25 / 17 |
| Débits temporaires | 998 |
| Évènements — dont modes / objectifs / réservoir / DDT / alertes | 112 — 60 / 13 / 9 / 7 / 23 |
| Profil | 1 |
| **Total** | **3 169** |

Écarté, et compté comme tel :

| Raison | Lignes |
| :-- | --: |
| Doublon dans le fichier (glycémies surtout) | 1 361 (1 347 + 14) |
| `pump settings` → sert au profil, pas de traitement | 12 |
| `cgm settings` → hors périmètre | 12 |

Avec la fenêtre par défaut (48 h), le même export donne **538 glycémies et 323 traitements** :
c'est le choix de ne pas réécrire une semaine 96 fois par jour.

Le second export du même jour (`GLOOKO_XT_Export_20261024.csv`, 4 087 lignes) donne 1 727
glycémies et 996 traitements — dont 862 débits : les deux comptes sont reproductibles, et
c'est ce qui permet de vérifier un import sans regarder Nightscout.

## 4. Comment faire une simulation (dry run)

1. **Réglages → Nightscout** : adresse et `API_SECRET` enregistrés (le serveur doit répondre
   à « Tester la connexion »).
2. **Réglages → Glooko** : activer « Importer les données Glooko », activer « Simulation
   (aucune écriture) », choisir la fenêtre **168 h** pour couvrir un export d'une semaine.
3. Toucher **Synchroniser maintenant**. Le service lance l'export (si l'accessibilité est
   active) ou reprend le dernier CSV présent, puis affiche son rapport.
4. Lire le résumé sous « Dernier passage ». En simulation, il commence par `Simulation :` et
   donne des nombres, jamais un succès : importé le 24/10 vers 07:30 avec la fenêtre 168 h,
   l'export réel donnerait
   `Simulation : 1 978 glycémie(s), 1 135 traitement(s), profil seraient importés`
   (les nombres baissent à mesure que le fichier vieillit, la fenêtre glissant avec l'heure).

En simulation : aucune requête d'écriture, aucune suppression, `lastSuccessMs` inchangé, et le
profil n'est pas marqué comme écrit.

## 5. Vérifier côté Nightscout

```powershell
$secret = "…"   # API_SECRET en clair
$sha1 = [System.BitConverter]::ToString(
  [System.Security.Cryptography.SHA1]::Create().ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes($secret))).Replace('-','').ToLower()

# combien de documents portent notre signature ? (v1 : elle accepte toujours la clé d'API)
curl.exe -s -H "api-secret: $sha1" "https://VOTRE-SERVEUR/api/v1/treatments?find[enteredBy]=Glooko-CamAPS&count=1"

# même question en v3, si le serveur l'ouvre à votre identifiant
curl.exe -s -H "api-secret: $sha1" "https://VOTRE-SERVEUR/api/v3/treatments?count=1&find[enteredBy]=Glooko-CamAPS"
```

Les documents importés portent `enteredBy = Glooko-CamAPS` et, pour les glycémies,
`device = Glooko-CamAPS` : c'est ce qui les distingue d'une saisie manuelle et de xDrip+, et
c'est ce qui fonde la priorité des sources.

## 6. Le chemin de l'export, relevé sur l'appareil

Relevé le 24/10/2026 sur le Hammer (Glooko XT 3.19.0, paquet `com.diabnext.diabnext`, écran
720×1612, interface en français). Le nom du paquet ne contient pas « glooko » — d'où la
première tentative infructueuse.

| Étape | Ce qui est touché |
| :-- | :-- |
| 1 | `Paramètres` (barre du bas) |
| 2 | `Compte` (bandeau du haut) |
| 3 | `Export de données` — carte **sous la ligne de flottaison**, atteinte en faisant défiler |
| 4 | Ligne `Intervalle de date` → boîte de dialogue (`7 jours` par défaut) → `OK` |
| 5 | `EXPORT` |
| 6 | L'application extrait sur son serveur, puis ouvre la feuille de partage : on y touche `Meal Announcer` |

Après l'étape 4, l'écran affiche `17/10/2026 → 24/10/2026` ; avant, il affiche des dates
d'attente de 2019.

**Le piège.** Ces dates de 2019 ne sont pas décoratives : presser `EXPORT` sans passer par la
boîte de dialogue exporte cet intervalle vide. L'automatisation lit donc les deux dates
(format `jj/MM/aaaa`) après validation et s'arrête sur
`DATE_RANGE` si la borne haute a plus de deux jours — un export vide ne doit jamais passer
pour un import réussi à zéro document.

**Le fichier.** `collected_data…csv`, partagé par `content://`. Il n'est jamais écrit dans
« Téléchargements » : `GlookoCsvSource` ne le trouvera pas, et c'est `GlookoShareActivity`
qui le reçoit et lance l'import. Le portail web (`my.glookoxt.com`), lui, produit un
`GLOOKO_XT_Export_….csv` téléchargeable — c'est de là que viennent les exports d'essai, et ce
chemin reste valable en le partageant à l'application.

## 7. Repères de sécurité

- Aucun écran de CamAPS FX n'est lu ni touché ; le service d'accessibilité ne sert qu'à
  Glooko, et seulement pendant l'export.
- Aucune dose n'est calculée : les montants viennent du CSV, jamais d'une estimation.
- Une valeur absente reste absente : pas de cible inventée, pas de durée inventée.
- Aucune donnée de santé dans les journaux, le résumé ou les préférences ; le CSV n'est pas
  versionné.
- Un fichier tronqué est refusé en bloc plutôt qu'importé à moitié.

## 8. Les deux API de Nightscout, et laquelle est utilisée

Nightscout expose deux interfaces, et elles ne savent pas la même chose.

| | v3 (`/api/v3`) | v1 (`/api/v1`) |
| :-- | :-- | :-- |
| Identifiant | token d'accès (souvent exigé) | clé `API_SECRET` (SHA-1), ou token |
| Écrire un document | `POST` sur la collection, mise à jour si l'`identifier` est connu | `POST` seul : ajout |
| Supprimer | par `identifier` | par l'`_id` du serveur |
| Profil | écriture (`POST /profile`) | lecture seulement (`GET /profile`) |

L'application **demande** au serveur, à la première écriture, par une lecture d'un document v3
(`GET /api/v3/entries?limit=1`). Un 200 garde la v3 ; un 401, 403 ou 404 signifie que la v3
n'est pas ouverte à cet identifiant — c'est le cas du serveur de l'utilisateur, mesuré le 24/10/2026
sur `entries`, `treatments` et `profile` — et l'import continue en v1. Le choix est gardé pour
le reste du processus, et refait si l'adresse ou le secret changent.

En v1, l'import reste rejouable sans doublon, mais autrement : la v1 ne sait pas mettre à
jour, c'est donc la **lecture préalable** (les `identifier` déjà présents sur la plage) qui
empêche la réécriture, et le retrait d'un doublon passe par l'`_id` que cette lecture a
rapporté.

**Le profil** est la seule perte : la v1 ne l'écrit pas. Le résumé du passage le dit alors
(« profil non envoyé (token d'API nécessaire) ») au lieu d'échouer ou de se taire. Il suffit
de créer un token dans Nightscout (Admin Tools → Subjects, permission `api:*`) et de le
saisir à la place de la clé pour que le profil soit écrit et que les écritures repassent
en v3 — sans changer le code.

**La lecture v1 se fait par tranches de 12 h**, 500 documents demandés par page. Une page
pleine est tenue pour peut-être tronquée par le serveur : elle n'est pas retenue, et
l'intervalle est relu en deux moitiés jusqu'à ce qu'aucune page ne soit pleine. Une lecture
incomplète ferait réécrire des repas déjà présents.

## 9. Recette sur l'appareil — 24/10/2026

La chaîne entière a été jouée deux fois de suite sur le Hammer, avec l'APK de debug, Glooko en
français, le service d'accessibilité actif, la fenêtre de 48 h et « Importer aussi les
glycémies » désactivé.

| | Ce qui s'est passé | Ce que l'écran a dit |
| :-- | :-- | :-- |
| 1er passage | export piloté, partage, import : **326 documents** écrits, puis une coupure réseau a arrêté la boucle | « Débits temporaires de 2026-10-23 20:14:00 : Impossible de joindre Nightscout : vérifiez la connexion. » |
| filet de sécurité, 4 min plus tard | le passage périodique a repris un export et complété les **5 documents** manquants | — |
| 2e passage | rien à écrire | « rien de neuf — 331 document(s) déjà à jour · profil non envoyé (token d'API nécessaire) » |

Côté serveur, interrogé en v1 :

- **331 traitements**, **331 identifiants distincts**, tous des UUIDv5 : aucun doublon, même
  après trois imports du même fichier.
- Temp Basal 285, Note 26, Carb Correction 10, Announcement 5, Correction Bolus 2,
  Insulin Change 2, Meal Bolus 1 — du 22/10 07:47 au 24/10 05:52 (UTC).
- Les valeurs sont celles du CSV : 20 g le 23/10 à 19:51 ; 50 g et 9,89 U le 22/10 à 20:59,
  avec la note « CamAPS a calculé 30.0 U » ; une alerte « Hyperglycémie : 286.01 mg/dl ».
- **Aucune glycémie** écrite (réglage désactivé) : xDrip+ reste la seule source de glycémie.
- Le profil `l'utilisateur` du serveur est **intact** : aucun document `Glooko-CamAPS` n'a été créé,
  la clé d'API n'ouvrant pas l'écriture du profil (§ 8).

Deux choses apprises, corrigées en **0.5.1** :

- Une coupure passagère laissait un passage **partiel** (326 sur 331), et le résumé disait la
  cause sans dire combien de documents étaient déjà écrits. Désormais chaque écriture est
  réessayée une fois quand l'échec est passager (`TIMEOUT`, `NETWORK`, `SERVER`) — deux
  tentatives, deux secondes d'écart — et le résumé d'un passage interrompu annonce les comptes
  avant la cause : « 326 document(s) écrit(s), puis : … — relancez, rien ne sera doublé. »
  Un refus définitif (clé refusée, document invalide) n'est pas réessayé : il le serait pour
  rien. Le filet de sécurité reste la seconde ligne de défense, pas la première.
- Ce serveur ne contient **que** ces traitements : ni glycémie, ni `devicestatus`. Les règles
  de priorité « xDrip+ fait foi » n'ont donc pas encore de contrepartie à comparer tant que
  xDrip+ ne publie pas vers ce Nightscout. Depuis le 24/10, xDrip+ publie bien ses glycémies
  (source « Companion App », REST `api/v1`) : la priorité s'exerce maintenant sur des données
  réelles.
