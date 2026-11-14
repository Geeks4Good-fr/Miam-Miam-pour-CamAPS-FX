# Décisions techniques

Chaque décision note **pourquoi**, pas seulement **quoi** : dans six mois, la raison
comptera plus que le choix.

## 1. `core:model` est un module Kotlin/JVM, pas Android

Les règles de calcul des glucides ne doivent pas pouvoir appeler le framework Android par
distraction. Un module JVM rend l'interdiction structurelle, et permet de lancer les tests
en une seconde, sans émulateur ni appareil.

## 2. `minSdk 29` plutôt que 26

`java.time` (Instant, LocalDate, Duration) est disponible à partir d'Android 10 sans
désucrage. Cela évite une dépendance de plus (kotlinx-datetime) pour un besoin modeste :
horodater un repas et formater une date. Les deux téléphones de test sont bien au-delà.

## 3. Les énumérations sont stockées par leur **nom**, jamais par leur ordinal

Une colonne qui contient `2` ne dit rien : si l'énumération est réordonnée, un repas
« envoyé » devient « en attente » sans que personne ne s'en aperçoive. Stocker `"SENT"`
coûte quelques octets et rend la base lisible à l'œil nu. `Converters.kt` lit de façon
tolérante : une valeur inconnue retombe sur une valeur sûre au lieu de faire planter
l'ouverture de l'application.

## 4. Le total des glucides n'additionne **que** ce qui est chiffré

Un aliment dont on ignore les glucides n'est pas « zéro glucide ». `MealTotals` renvoie
donc un total, la liste des lignes résolues et un drapeau `incomplete` ; l'interface doit
afficher « 40 g (2 aliments non comptés) » plutôt qu'un 40 g silencieusement faux.

## 5. « Une tasse » vaut 200 ml, et une tranche n'est jamais convertie

Deux conventions, assumées et affichées :

- une tasse = 200 ml (usage français, différent de la cup américaine de 240 ml) ;
- une tranche n'a pas de poids fiable : `UnitConversions` refuse la conversion et
  `CarbCalculator` répond `MISSING_PORTION_SIZE` au lieu d'inventer 30 g.

Une valeur inventée est plus dangereuse qu'une valeur manquante : elle est crédible.

## 6. Le détail d'une ligne de repas est archivé en JSON

Les colonnes de `meal_items` ne gardent que ce qui sert à recalculer et à filtrer
(quantité, unité, glucides retenus). Le nom du produit, la source, la base de calcul
(pour 100 g / pour une unité) et le texte dicté partent dans `payload_json`. Ce choix
garde le schéma SQL stable quand le domaine évolue, sans perdre l'information : l'historique
doit rester relisible, pas seulement récapitulable.

## 7. Rien n'est simulé

Tant qu'un jalon n'est pas écrit, l'écran le dit : « Cette étape arrive à un prochain
jalon : rien n'a été envoyé. » Afficher un succès factice dans une application qui touche
à l'insuline serait la pire des erreurs de conception.

## 8. La compilation est validée par étapes

AGP 9, KSP, Hilt et Room sont récents et leur combinaison est le vrai risque du projet.
Le premier build valide la chaîne entière sur un écran vide, avant d'ajouter une seule
fonctionnalité : un échec d'outillage ne doit pas se confondre avec un bug de logique.

## 9. `jvmToolchain(17)` alors que le JDK installé est 21

AGP 9 exige un JDK 17 ou plus pour tourner ; la toolchain 17 fixe la cible du bytecode et
rend le build reproductible quel que soit le JDK lancé. Gradle a déjà téléchargé ce JDK 17
dans `~/.gradle/jdks`, donc la résolution se fait hors ligne.

## 10. Le nom du projet corrige la faute du cahier des charges

Le document d'origine s'appelle « Meal Annoucer for CamAPS FX ». L'application s'appelle
**Meal Announcer** (paquet `com.najah.mealannouncer`) ; le cahier des charges est conservé
dans le dépôt privé de développement (non publié ici).

## 11. Un repas n'entre dans l'historique que sur un geste explicite

Rien ne s'enregistre en arrière-plan : l'écran de confirmation montre le total, son détail
et la liste des aliments **non comptés**, puis attend « Enregistrer le repas ». Un total
incomplet annoncé sans le dire est un total faux, et un historique qui se remplit tout seul
ne serait plus une trace fiable de ce qui a été annoncé.

Le bouton ne peut pas s'exécuter deux fois pour le même repas : l'état d'enregistrement
(`Idle` → `Saving` → `Saved`) bloque un second appui, y compris après une rotation.

## 12. Le repas à confirmer vit dans le ViewModel, pas dans la navigation

`MainActivity` ne transporte qu'une chaîne de route. Le repas en cours est porté par
`ConfirmationViewModel`, porté par l'activité : une rotation de l'écran ne le perd pas, et
la navigation reste un simple état, sans bibliothèque. Après une fermeture complète de
l'application, l'écran le dit (« Ce repas a été perdu ») plutôt que d'afficher un faux 0 g.

## 13. L'identifiant d'une ligne de repas est son index, pas son nom

La première version fabriquait la clé à partir du nom du produit et de la quantité. Deux
lignes identiques (« Prince ×2 » ajouté deux fois) auraient alors la même clé et
s'écraseraient silencieusement à l'insertion. La clé est maintenant `repas-index`, ce qui
rend les doublons possibles et sans conséquence.

## 14. La note de repli est un libellé court, pas une liste de noms

Un repas enregistré sans note reçoit `Prince ×2 + Candy Up 200 ml` (via
`ResolvedMealItem.shortLabel()`), et non `Prince + Candy Up` : la quantité est ce qui
permet de reconnaître le repas des mois plus tard, et elle était absente du premier essai.

## 15. `test`, et pas `testDebugUnitTest`, pour conclure qu'une suite est verte

`core:model` est un module Kotlin/JVM : ses tests tournent avec `test`, pas avec
`testDebugUnitTest`, qui n'existe que pour les modules Android. Le premier rapport de build
annonçait donc 38 tests alors que 8 tests du domaine n'avaient pas été exécutés. La commande
de référence est désormais `gradlew test` (46 tests, sans le lint que `check` déclenche), et
le README le dit explicitement : un chiffre de tests qui ne bouge pas alors qu'on vient
d'en ajouter est un signal d'alarme.

## 16. L'en-tête `api-secret` porte le SHA-1 de l'`API_SECRET`, pas le secret

Vérifié sur le serveur d'essai le 24/10/2026 : `api-secret: MON-SECRET` renvoie `401`,
`api-secret: sha1("MON-SECRET")` renvoie `200`. Nightscout hache le secret reçu avant de
le comparer ; envoyer le secret en clair ne peut donc jamais fonctionner. Le client hache
pour une clé d'API et envoie un token d'accès tel quel, puisque Nightscout ne hache pas les
tokens.

## 17. Le secret vit dans le Keystore, jamais dans les préférences

`AndroidX security-crypto` est en fin de vie et n'apporte rien ici : une clé AES-256 dans le
Keystore matériel et un chiffrement AES-GCM à usage unique (`SecretStore`) suffisent. Le
secret est stocké chiffré, la clé ne sort pas du téléphone, et une sauvegarde du dossier de
l'application ne livre rien de lisible. `NightscoutConfig` ne contient que `hasSecret` :
l'écran de réglages peut afficher son état sans jamais manipuler la valeur.

## 18. L'URL est normalisée à l'enregistrement, pas à l'envoi

Les gens collent ce qu'ils ont : `https://user@hote/api/v1/`. `NightscoutUrl.normalize()`
retire l'identifiant devant l'hôte, le suffixe `/api/v1` et les barres finales, met le
schéma et l'hôte en minuscules, et garde un éventuel chemin de déploiement
(`https://hote/nightscout`). L'URL enregistrée est celle qui sera réellement utilisée, donc
l'écran peut l'afficher sans surprise. Une adresse qui n'est pas `http(s)` est refusée.

## 19. L'envoi passe par WorkManager, et le repository ne réessaie pas

Un repas ne doit pas être perdu parce que le téléphone n'avait pas de réseau au moment de la
confirmation. WorkManager attend la connexion, espace les essais (30 s puis doublement),
survit au redémarrage. La décision de réessayer appartient au worker
(`runAttemptCount < 5` et erreur réessayable) ; le repository se contente de dire ce qui
s'est passé. Après le cinquième échec, l'envoi est marqué `FAILED` dans le journal : un
échec silencieux serait un mensonge par omission.

## 20. Un repas déjà envoyé n'est jamais renvoyé

Avant tout envoi, le statut du journal est relu : `SENT` signifie qu'on s'arrête là. C'est
la seule protection contre les doublons pour ce jalon, et elle est locale (donc gratuite).
Une vérification distante (« ce traitement existe-t-il déjà ? ») demanderait une requête de
lecture supplémentaire par repas ; elle est notée comme amélioration, pas comme acquis.

## 21. La simulation ne laisse aucune trace dans le journal

En simulation, rien ne part et le statut du repas reste `NOT_REQUESTED` : écrire `SENT`
ferait croire à un envoi qui n'a pas eu lieu, et l'historique est précisément ce qu'on
consulte pour savoir ce qui est parti. L'écran de confirmation le dit (« simulation, rien
n'a quitté le téléphone »), et la simulation ne s'active que si l'envoi est activé — sinon
elle ne simulerait rien.

## 22. Les réglages sont un écran à part, pas une boîte de dialogue

Adresse, secret, type d'identifiant, activation, simulation et test de connexion demandent
de la place et de la lisibilité ; une boîte de dialogue aurait été tronquée sur le
téléphone. Un module `feature:settings` séparé garde aussi la dépendance à
`data:nightscout` hors de l'accueil.

## 23. Le basal s'écrit minute par minute, et la dernière valeur de la minute gagne

L'export réel contient 1 211 lignes de basal pour 998 minutes distinctes : la pompe réécrit
son débit à chaque lecture, et 203 minutes portent deux valeurs qui se contredisent
(`08:36` → `0` puis `7,36`). Nightscout n'a pas de place pour deux débits à la même minute :
il faut choisir. Garder **la dernière ligne de la minute** est le seul choix qui n'invente
rien — c'est la valeur que la pompe a écrite en dernier — et qui garde exactement une valeur
par minute.

Chaque point court jusqu'au suivant (borné à 3 h pour qu'une nuit sans ligne ne devienne pas
un débit éternel), le dernier point est plafonné à 30 minutes faute de savoir ce qui suit, et
le **dernier point d'avant la fenêtre est conservé** comme point d'ancrage : sans lui, la
courbe commencerait par un trou au début de la fenêtre.

Aucun filtre sur le type de délivrance : filtrer sur une graphie qu'on n'a pas vérifiée
risquerait de perdre une semaine entière en silence.

## 24. Un bolus étendu reste un bolus étendu, un bolus repas reste un bolus repas

Deux lignes `extended` existent dans l'export réel. Les convertir en `Meal Bolus` affirmerait
un repas qui n'a pas eu lieu ; elles deviennent donc un `Combo Bolus` avec leur durée. Quand
la durée est inférieure à la minute (0,03 U sur 480 ms), elle n'est pas une durée : elle
part en note sur le bolus voisin (« bolus étendu 0,03 U en 480 ms ») plutôt que d'être
arrondie à zéro.

Le couple `normal` + `calculator` d'une même minute est **un seul** document : c'est un seul
geste. Quand les deux montants diffèrent (22/10 22:59, 9,89 U livrés contre 30 U calculés),
le montant livré fait foi et la valeur calculée part en note — l'écart reste visible au lieu
d'être moyenné.

## 25. Les modes et les objectifs temporaires deviennent des notes datées, sans cible inventée

`pumpMode - Closed loop`, `Closed loop`, `Liberty`, `Pining`, `boost`, `easeOff` : on sait
quand ils ont commencé et combien de temps ils ont duré, pas les cibles que CamAPS a
appliquées pendant ce temps. Écrire une cible plausible serait inventer un réglage médical.
Une note datée dit exactement ce qui s'est passé, et rien de plus.

`ReservoirChange` devient `Insulin Change` (type d'évènement que Nightscout connaît déjà),
la dose totale quotidienne une note, et toute « Alerte de sécurité » un `Announcement` : dans
la chronologie, une alarme doit se voir. Les lignes `pump settings`, elles, ne produisent
aucun traitement — elles **sont** le profil.

## 26. Le profil n'est réécrit que si son contenu change

L'export réel contient douze copies des mêmes réglages, qui ne diffèrent que par `time`. Le
profil a un identifiant fixe (un seul profil `Glooko-CamAPS`), et une empreinte SHA-256 de
son contenu — sans `time` ni `deviceId` — est gardée localement : sans elle, l'import
réécrirait le profil toutes les quinze minutes, et son historique avec. Quand le contenu
change vraiment, il est réécrit.

## 27. Les identifiants sont des UUIDv5 déterministes

L'API v3 reconnaît un document à son `identifier` et met à jour au lieu de créer. Un UUID
aléatoire à chaque passage dupliquerait la semaine entière au deuxième envoi. Un UUIDv5
(SHA-1, dans un espace de noms lui-même dérivé de l'espace DNS) de
`famille|horodatage|clé métier` donne le même identifiant au même fait, pour toujours — y
compris après réinstallation, puisque rien de local n'entre dans le calcul.

La clé métier est ce qui distingue deux faits de la même minute (bolus, glucides, mode), et
la durée fait partie de la clé des évènements : deux modes différents à 10:03 coexistent.

## 28. La priorité se décide en lisant, pas en espérant

L'ordre est **xDrip+ > Meal Announcer > Glooko**. Glooko est le journal de la pompe : il fait
foi sur ce qui a été délivré, mais xDrip+ est plus près du capteur et Meal Announcer est la
déclaration de l'utilisateur. Avant d'écrire, l'import lit la fenêtre une fois par collection,
puis :

- identifiant déjà présent → sauté (« déjà présent ») ;
- glycémie à moins de 2 min 30 d'une glycémie d'une autre source → sautée ; repas à moins de
  5 min d'un repas d'une autre source → sauté ;
- nos propres glycémies doublées par une source plus prioritaire sont **retirées** avant
  l'écriture : les garder laisserait deux courbes superposées. Aucun traitement n'est
  supprimé, jamais.

Le retrait se fait avant les écritures, sinon le document écarté serait réécrit juste après.

## 29. L'automatisation Glooko cherche des libellés, pas des positions

Un clic sur des coordonnées casse à la première mise à jour de Glooko, en cliquant
silencieusement autre chose — dans une application qui journalise de l'insuline. Les libellés
sont aussi la seule chose que le service d'accessibilité lit de façon fiable. La liste des
libellés est une donnée (`GlookoAutomationSpec`), l'échec est explicite (`STEP_NOT_FOUND` avec
le libellé cherché) et un mode **cartographie** écrit les libellés de l'interface dans un
fichier, pour corriger la liste à partir de preuves plutôt qu'à l'aveugle.

## 30. La fenêtre d'import est bornée, et l'export n'est jamais versionné

Un export hebdomadaire fait 4 807 lignes ; le rejouer entièrement toutes les quinze minutes
réécrirait une semaine d'historique 96 fois par jour. La fenêtre (24 h par défaut, jusqu'à
7 jours) borne le travail et le risque ; ce qui est plus ancien est compté
« hors fenêtre », pas écarté en silence.

Le CSV lui-même reste hors du dépôt : il contient une semaine de glycémies et d'insuline.

## 31. Le partage manuel existe parce qu'une automatisation peut cesser de fonctionner

Si Glooko change son interface, ou si l'accessibilité est désactivée, la fonctionnalité doit
rester utilisable : `GlookoShareActivity` accepte le CSV par « Partager » et lance le même
import. L'automatisation est le confort, pas la dépendance.

Une **activité** transparente, et non un `BroadcastReceiver` : mesuré sur l'appareil le
24/10/2026, la feuille de partage n'énumère que des activités — un récepteur, même exporté,
n'y apparaît jamais. L'activité ne dessine rien (`Theme.Translucent.NoTitleBar`), ne reste pas
dans les récents, et se ferme dès l'import lancé.

## 32. Le service d'export est un service avant-plan, pas un simple worker

Piloter l'interface d'une autre application prend des dizaines de secondes et doit survivre à
l'extinction de l'écran ; un worker serait un candidat à l'arrêt en pleine manœuvre. Le type
`dataSync` est le type honnête, la notification dit ce qui se passe, et le worker ne fait que
réveiller le service. Rien ne tourne quand la fonctionnalité est désactivée.

## 33. L'export de Glooko passe par le partage, et l'automatisation vérifie la période

Chemin relevé sur l'appareil le 24/10/2026 (Glooko XT 3.19.0, `com.diabnext.diabnext`) :
Paramètres → Compte → « Export de données » → intervalle → EXPORT, puis la feuille de partage
d'Android avec un `collected_data….csv`. Trois conséquences, toutes contre-intuitives :

- **Le paquet ne s'appelle pas `com.glooko.*`** : c'est `com.diabnext.diabnext`, et « glooko »
  n'apparaît que dans les classes (`glooko.xt.…`). Chercher le paquet par son nom d'application
  échoue.
- **Le CSV n'est jamais écrit dans « Téléchargements »** : il n'existe que par le partage. La
  découverte par `MediaStore.Downloads` ne le verra pas ; c'est `GlookoShareActivity` qui le
  reçoit, et le service n'attend donc plus de fichier après avoir déclenché l'export.
- **L'écran garde des dates d'attente de 2019** tant que la boîte de dialogue de l'intervalle
  n'a pas été validée. Presser EXPORT à ce moment produit un fichier vide. Un import naïf
  l'aurait compté comme un succès à zéro document — exactement le mensonge que ce projet
  s'interdit. L'automatisation valide donc l'intervalle, relit les deux dates (`jj/MM/aaaa`) et
  refuse d'exporter (`DATE_RANGE`) si la borne haute a plus de deux jours.

## 34. L'export part à chaque repas, la cadence n'est qu'un filet de sécurité

Le cahier des charges prévoyait un passage toutes les quinze minutes. C'était une erreur de
conception, et l'usage l'a montrée : l'export prend l'écran du téléphone une quinzaine de
secondes, ce qu'on ne peut pas infliger quatre-vingt-seize fois par jour — et Glooko ne publie
de toute façon ses données que toutes les quelques heures (observation de l'utilisateur, 24/10/2026).
Un export toutes les quinze minutes aurait donc demandé l'écran sans rien rapporter.

Trois règles remplacent la cadence :

- **Un repas annoncé déclenche le passage**, trois minutes plus tard. Un repas est le moment où
  l'on veut le contexte d'insuline et de glucides ; trois minutes, c'est le temps de saisir le
  bolus dans CamAPS FX sans qu'on vous vole l'écran, et le temps que Glooko publie.
- **Trente minutes séparent deux déclenchements**, et un export manuel compte comme les autres :
  `lastExportAttemptMs` est posé par le service, quel qu'en soit l'auteur. Deux repas
  rapprochés ne valent pas deux prises d'écran.
- **Un filet de sécurité périodique** (6, 12 ou 24 h, 24 h par défaut) rattrape les journées
  sans repas annoncé. Il ne remplace pas le déclencheur par repas, il le complète.

La complétude ne dépend pas de la fréquence : chaque export couvre sept jours et les
identifiants sont déterministes, donc un passage manqué ne perd rien — il retarde le prochain.
Le réglage « Exporter à chaque repas annoncé » permet de revenir au seul filet de sécurité.

L'écran de confirmation affiche une ligne quand un passage est réellement programmé, et se
tait dans les trois autres cas (import coupé, réglage retiré, export trop récent) : il ne
promet que ce qu'il tient.

## 35. L'import demande au serveur quelle API il accepte, au lieu de la supposer

Le jalon 0.5.0 était écrit pour la v3 seule : `upsert` par `identifier`, lectures de plage en
une requête, profil écrit. La validation sur le serveur d'essai (24/10/2026) a montré que
cette hypothèse était fausse : la v3 répond **401** à la clé d'API sur `entries`, `treatments`
et `profile`, tandis que la v1 répond 200 partout — et `status.json` ne dit rien de cette
différence, ce qui explique que le test de connexion du jalon 0.4.0 l'avait laissée passer.

Deux solutions étaient possibles : exiger un token (créé à la main dans Nightscout), ou parler
aussi la v1. Exiger un token, c'est faire dépendre un import automatique d'une manipulation
que personne ne refera après une réinstallation ; la v1, elle, marche avec ce que
l'utilisateur a déjà saisi. **Les deux sont donc supportées, et le choix est demandé au
serveur** : une lecture d'un document v3 à la première écriture. 200 → v3 ; 401, 403 ou 404 →
v1 ; le résultat est mémorisé par couple adresse/identifiant.

Ce que la v1 ne sait pas faire est dit, pas contourné :

- **Pas de mise à jour** : l'absence de doublon repose entièrement sur la lecture préalable
  des `identifier` de la plage. C'est le même contrat qu'en v3 (identifier déjà présent → on
  n'écrit pas), mais il n'a plus de filet côté serveur.
- **Pas d'écriture de profil** (`/profile` n'expose que `GET`) : le passage se termine
  normalement et le résumé ajoute « profil non envoyé (token d'API nécessaire) ». Ce n'est ni
  un échec ni un silence ; le hash du profil n'est pas mémorisé, pour qu'un token ajouté plus
  tard fasse partir le profil au passage suivant.
- **Suppression par `_id`** : la lecture rapporte l'`_id` du serveur en plus de notre
  `identifier`, et la référence de document porte les deux.
- **Lecture par tranches** : la v1 ne prend pas de bornes `from`/`to` mais des filtres Mongo
  (`find[date][$gte]`, `find[created_at][$gte]`), et `count` borne la réponse sans que l'on
  sache ce que le serveur fait d'une demande trop grande. Une page pleine est donc tenue pour
  peut-être tronquée : elle est jetée, l'intervalle est relu en deux moitiés, jusqu'à ce
  qu'aucune page ne soit pleine. Une lecture incomplète ferait réécrire des repas déjà là —
  c'est le seul défaut que cet import ne peut pas se permettre.

Les chemins v1 ne sont pas devinés : ils sont recopiés du `swagger.json` que le serveur publie
lui-même (`/treatments` en `POST`/`DELETE`, `/profile` en `GET` seulement).

## 36. La voix passera par un appel HTTPS à Voxtral, pas par LiveKit

La question posée le 24/10/2026 était d'intégrer Voxtral en partant de la page LiveKit pour
Android. L'étude est dans `docs/VOXTRAL.md`. Trois architectures y étaient comparées ; la
première est retenue, et l'argument décisif n'est pas le prix mais **la forme de la panne**.

Voxtral est un service : le modèle tourne sur un serveur, jamais dans l'APK. Le SDK Android de
LiveKit ne fait que transporter une salle WebRTC, et le greffon Mistral de LiveKit n'existe
qu'en Python et Node.js. Choisir LiveKit, c'était donc ajouter un serveur LiveKit, un endpoint
qui signe les jetons (le secret de signature ne peut pas vivre dans une application mobile), et
un agent à superviser — pour prononcer une phrase de cinquante caractères six fois par jour.
Une annonce de repas aurait alors dépendu de cinq maillons, dont quatre hors du téléphone, et
son mode de panne le plus probable est le pire : la salle se connecte, personne ne parle.

Retenu à la place : `POST /v1/audio/speech` (`voxtral-mini-tts-3-0`) en direct depuis
`feature:voice`, avec un module `data:voxtral` calqué sur `data:nightscout` (transport injecté,
erreurs traduites, aucun test qui sorte sur Internet), la clé dans le `KeystoreSecretStore`
existant, et le choix de la voix dans `core:datastore`. Conséquences assumées :

- **Le réseau ne bloque jamais une annonce.** On tente Voxtral avec un délai court ; au-delà,
  la voix Android prend le relais immédiatement et la phrase est générée en tâche de fond pour
  la fois suivante.
- **Cache par empreinte** (`texte + voix + modèle + format`) : les phrases d'annonce sont
  stables, donc le coût réel tend vers zéro. Ordre de grandeur brut : 16 $ le million de
  caractères, soit moins d'un dixième de centime par annonce.
- **Aucune nouvelle permission** : `RECORD_AUDIO` est déjà déclaré, et le TTS n'en demande pas.
- **Pas de JitPack, pas de WebRTC, pas de Dagger 2.46 en runtime** : la politique de dépôts du
  projet (Google et Maven Central, avec filtres) reste intacte.

La dictée reste **hors ligne par défaut** (Vosk), conformément au blueprint qui promet qu'aucune
transcription ne part sur Internet. Un mode « dictée Voxtral » pourra exister plus tard, à côté,
désactivé par défaut, avec l'écran qui dit ce qui est envoyé. LiveKit ne reviendra que si un
vrai dialogue mains libres devient un besoin : il serait alors isolé dans son module et son
écran, pour que le chemin d'annonce d'un repas ne passe jamais par lui.

## 37. Une écriture Nightscout est réessayée une fois, et un passage interrompu dit ses comptes

**Contexte.** Le premier import réel (24/10/2026) a écrit 326 documents sur 331 puis s'est arrêté
sur « Impossible de joindre Nightscout ». Le serveur répondait de nouveau une seconde plus tard ;
les cinq documents manquants n'ont été repris que par le filet de sécurité, quatre minutes plus
tard, au prix d'un second export complet (une quinzaine de secondes d'écran pris) — et le résumé
affiché ne permettait pas de savoir s'il fallait tout refaire.

**Décision.** Deux mesures, dans `GlookoImporter` :

- **Un réessai, seulement pour les échecs passagers.** `TIMEOUT`, `NETWORK` et `SERVER` (la
  propriété `isRetryable` de `NightscoutErrorKind`) sont retentés une fois, deux secondes plus
  tard. `UNAUTHORIZED`, `NOT_FOUND` ou une réponse invalide ne sont **pas** retentés : la même
  requête échouera encore, et un refus de clé ne doit pas être maquillé en lenteur.
- **Le compte avant la cause.** Un passage interrompu s'affiche « 326 document(s) écrit(s),
  puis : … — relancez, rien ne sera doublé. » Le préfixe n'apparaît que s'il y a réellement des
  écritures et jamais en simulation, pour ne pas transformer un échec total en bonne nouvelle.

**Pourquoi pas plus.** Pas de troisième tentative ni de temporisation exponentielle : au-delà de
quelques secondes d'indisponibilité, c'est le filet de sécurité qui doit travailler, avec un
export neuf, plutôt que de retenir l'écran et la batterie. Les écritures étant idempotentes
(identifiants UUIDv5), un réessai ne peut pas créer de doublon — c'est ce qui permet de dire
« relancez, rien ne sera doublé » sans réserve.

**Vérifié.** Trois tests de politique dans `GlookoImporterTest` : panne passagère reprise sans
perdre de document (3 tentatives pour 2 écritures), panne persistante avec compte partiel
(« 1 document(s) écrit(s), puis : … »), refus définitif en une seule tentative. 167 tests, aucun
échec ; APK 0.5.1 installé sur le Hammer le 24/10/2026.

## 38. Deux moteurs de voix, et un consentement qui commande Voxtral

**Contexte.** Décision 36 avait arrêté l'architecture (appel HTTPS direct) mais laissait trois
points ouverts : où vivent les deux moteurs, comment la clé Mistral cohabite avec celle de
Nightscout, et ce qui se passe quand Voxtral est choisi sans être utilisable.

**Décision.** Un aiguillage explicite, dans `feature:voice` :

- **Une interface, deux implémentations qualifiées.** `VoiceEngineTts` rend `true` seulement si
  la phrase a été jouée. `AndroidTts` (hors ligne) et `VoxtralTts` sont injectés avec
  `@AndroidEngine` et `@VoxtralEngine` : le repli se lit dans la signature de `VoiceAnnouncer`,
  pas dans un commentaire. `VoiceAnnouncer` rend `SpokenBy` — ce qui a réellement parlé — pour que
  l'écran puisse dire la vérité sur un essai de voix.
- **Le consentement est une condition, pas une case décorative.** `VoiceConfig.effectiveEngine()`
  ne renvoie `VOXTRAL` que si le moteur est choisi **et** le consentement coché **et** une clé
  présente. Les trois sont vérifiés au même endroit, donc l'écran et l'annonce réelle ne peuvent
  pas diverger. `consentMistral` vaut `false` à la création, et décocher la case suffit à faire
  taire Voxtral sans effacer la clé.
- **Une clé, un coffre, deux entrées.** `VoxtralSecretStore` est une classe distincte de
  `KeystoreSecretStore` (alias `mealannouncer.voxtral`, clés `voxtral.*`) et **n'est pas** liée à
  l'interface `SecretStore` : deux secrets dans le même coffre auraient fini par se confondre, et
  effacer la clé Nightscout ne doit pas emporter la clé Mistral.
- **Le repli est testé, pas supposé.** `VoiceAnnouncerTest` couvre : Voxtral parle quand les trois
  conditions tiennent ; Android par défaut ; silence de Voxtral sans consentement ; silence sans
  clé ; repli quand la synthèse échoue ; repli quand le moteur lève.

**Pourquoi le cache.** Les annonces sont des phrases stables (`texte + voix + modèle + format`) :
`VoiceAudioCache` les garde sous `cacheDir/voxtral`, avec purge au-delà de 8 Mio, les plus anciens
partant d'abord. Sans lui, chaque repas repaierait le réseau ; avec lui, le coût réel tend vers
zéro et une annonce répétée reste instantanée — mais jamais au point de faire passer le réseau
avant la voix locale.

**Ce qui reste volontairement dehors.** Pas de dictée Voxtral (le blueprint promet une
transcription qui ne part pas sur Internet), pas de dialogue, pas de streaming : une phrase, un
fichier, une lecture.

## 39. Un jeton d'accès Nightscout s'échange en JWT pour écrire le profil

**Contexte.** Le serveur de l'utilisateur (v15.0.8, `authDefaultRoles: denied`) refuse le profil en v1 :
la clé d'API n'ouvre que les traitements, et l'écriture du profil exige la v3. Le symptôme était
un « profil non envoyé » persistant alors que les 331 traitements passaient.

**Décision.** Le client ne suppose plus l'API : quand `credentialType == ACCESS_TOKEN`, il échange
d'abord le jeton contre un JWT (`GET /api/v2/authorization/request/<token>`), garde ce JWT en
mémoire par couple base+jeton jusqu'à `exp − 60 s`, et ajoute `Authorization: Bearer <jwt>` à
chaque requête **tout en gardant `api-secret` = jeton** pour la compatibilité v1. Un jeton refusé
n'est pas réessayé indéfiniment : l'appelant retombe sur le comportement v1 et le résumé le dit.

**Vérifié.** Test `token d'accès — token en api-secret et JWT en Bearer` (le faux transport rend
d'abord `{"token":"<jwt>"}`, puis la réponse normale et vérifie les trois points). La preuve sur
l'appareil — un passage Glooko dont le résumé dit « profil envoyé » — reste à faire : elle demande
l'écran allumé et un téléphone déverrouillé.

## 40. Un échec de voix ne peut pas être muet

**Contexte.** Le 26/10, l'essai de voix affichait « Lu par la voix Android. » sans aucune cause
pendant qu'une heure : l'appel Voxtral échouait, mais `VoiceAnnouncer` avalait l'exception sans
rien retenir et `VoxtralTts.speak` n'était pas gardé. Le seul indice visible était un statut
trompeur.

**Décision.** Trois règles, applicables à tout moteur ajouté plus tard :

1. un moteur **pose** sa cause (`lastIssue`) avant de rendre `false`, et il attrape tout ce qui
   peut lever, `CancellationException` exceptée ;
2. l'aiguillage retient ce qu'il a avalé (`VoiceAnnouncer.lastSwallowed`) et l'écran d'essai le
   montre, faute de quoi un repli ressemble à un succès ;
3. un refus HTTP porte la réponse du serveur (`401 : {"detail":"Invalid API Key"}`), pas seulement
   son code — c'est ce qui distingue une clé fausse d'un en-tête perdu ou d'un compte fermé.

**Conséquence assumée.** L'écran d'essai peut afficher un message technique court, en anglais s'il
vient du serveur. C'est préférable à un silence : pour un dispositif médical, « je ne sais pas »
doit être dit.

## 41. La base Open Food Facts est embarquée, et l'index est rempli par popularité

**Contexte.** Chercher chaque aliment sur l'API d'Open Food Facts au moment de manger échoue trois
fois : c'est lent, sans réseau il n'y a plus rien, et cela envoie le repas à un service extérieur
sans nécessité. L'export public complet tient pourtant sur le téléphone. L'utilisateur a demandé
explicitement que la base soit **installée sur le téléphone**, et qu'Internet ne serve que si
l'information n'y est pas.

**Décision.** Trois choix, et ils se tiennent ensemble :

1. **La base complète, filtrée une fois sur le PC.** `construire_base.py` garde les 3 065 819
   produits nommés et chiffrés en glucides (626 Mo) et les copie sur l'appareil. L'application la
   lit en lecture seule ; elle ne télécharge rien, la mise à jour est un geste volontaire.
2. **L'index est rempli dans l'ordre des `scans` décroissants.** La recherche lit donc les 2000
   premières correspondances — les produits les plus connus — au lieu de trier toutes les
   correspondances. Mesuré : « chocolat » (141 393 correspondances) passe de 17 s à 45 ms.
3. **La base du téléphone passe avant le réseau**, mais **après** les favoris de l'utilisateur, et
   chaque nom trouvé repasse par `FoodNameMatcher`.

**Conséquence assumée.** Le fichier n'est pas à jour : un produit récent manque, d'où les cinq
essais progressifs de `OffText.matchAttempts` et le repli final sur la recherche web de Mistral.
L'étiquette de chaque ligne dit « hors ligne » ou « en ligne » : L'utilisateur voit quelle source a
répondu, et cela sert aussi de test — une valeur marquée « hors ligne » prouve que le chemin local
fonctionne.

**Ce que cela ne change pas.** Un aliment qu'on ne sait pas chiffrer reste affiché comme non
chiffré et n'est jamais compté zéro ; aucune valeur n'est inventée pour combler un trou.

## 42. Le lien avec Home Assistant passe par OkHttp, derrière une interface de transport

**Contexte.** Le canal maison (`docs/VOIX-MAISON.md`) exige une WebSocket sortante vers Home Assistant.
Android n'a pas de client WebSocket intégré, et le projet n'utilise jusqu'ici que `HttpURLConnection`.

**Décision.** OkHttp 4.12.0 (Maven Central, déjà présent dans le cache Gradle) sert **uniquement** de
transport réel, derrière `WebSocketTransport`. Tout le protocole Home Assistant (authentification,
numéros de messages, abonnement, appels de service, délais, coupures) vit dans `HomeAssistantSession`
et s'éprouve avec un faux serveur, sans réseau ni Android — comme les clients Nightscout et Voxtral.

**Conséquence assumée.** Une dépendance tierce de plus, limitée au module `:data:homeassistant`.
Écrire RFC 6455 à la main (masquage, fragmentation, pings) aurait été plus risqué pour un lien dont
dépend le dialogue du repas.

**Garde-fous.** Le jeton (compte HA non administrateur) est dans son propre coffre Keystore et n'est
jamais journalisé ; un jeton refusé n'est jamais réessayé (HA bannit l'adresse après 5 échecs) ; une
valeur douteuse du champ de dictée (`unknown`, morceaux manquants) ne lance jamais de repas.

## 43. Les glucides sont relus par un modèle qui raisonne, et des garde-fous codés en dur passent avant

**Contexte.** Le 29/10/2026, premier essai réel de la voix de la maison : « j'ai mangé un thé avec trois
sucres » a été annoncé **123 g**. Deux erreurs s'additionnaient : le thé infusé était chiffré avec une
fiche de **thé en poudre sucré** (37 g pour 100 g, appliqués à 250 ml → 92,5 g) et le petit modèle
(`mistral-small-latest`) avait pris **10 g par morceau de sucre** (30 g au lieu de 16,5). Rien n'a été
écrit (l'automate CamAPS a échoué, et l'utilisateur n'aurait pas confirmé), mais l'appli l'avait dit à voix haute
avant de demander le type. Cause de fond : la décision d'origine interdisait au modèle tout jugement sur
les glucides ; l'appli appliquait la fiche trouvée sans jamais se demander si le résultat avait du sens.
L'utilisateur exige « toute l'intelligence possible ».

**Décision.** Quatre changements, éprouvés sur l'API réelle avant d'être codés :

- **A. Un modèle par tâche.** Conversation, lecture d'une phrase, recherche web : `mistral-large-latest`.
  Relecture : `magistral-medium-latest` (modèle de raisonnement ; sur cinq repas pièges il a tout vu,
  y compris le poids du sucre que Large laissait passer, sans aucun faux signalement, en 1,5 à 2 s).
  Photo d'assiette : `mistral-medium-latest` (Large ignore l'image jointe, vérifié).
- **B. La relecture** (`MealReview`, `MealReviewer`). Quand la conversation pense avoir fini, le modèle
  de raisonnement reçoit les mots de la personne et chaque ligne (fiche, valeur pour 100 g / 100 ml,
  quantité, résultat), réfléchit (`raisonnement`), donne **sa propre estimation** par ligne et une
  correction (autre nom de produit, bon poids, ou sa valeur quand aucune fiche ne convient). L'appli
  juge l'écart elle-même (`CarbSanity.discrepant` : plus de 8 g **et** plus d'un quart), applique la
  correction, fait relire une seconde fois ; un écart qui reste devient une question (une fois par
  ligne) puis un **doute**. Un doute — ou une relecture impossible — **interdit l'envoi automatique
  vers CamAPS** : le repas attend à l'écran.
- **C. Des garde-fous codés en dur** (`CarbSanity`), sans IA : une boisson (infusée ou mesurée en volume)
  ne prend jamais la valeur d'une poudre, de feuilles, d'un sirop ou d'une fiche sèche ; un morceau de
  sucre pèse 4 à 7 g (5,5 g si le modèle s'égare) ; au-delà de 120 g sur une ligne ou 150 g au total,
  doute. Avant la question du type, l'enceinte (ou le téléphone) dit **le détail ligne par ligne**
  (« thé infusé nature : 0,5 grammes, sucre en morceaux : 16,5 grammes »).
- **D. La consigne de conversation réécrite** : raisonnement d'abord, boisson et ajout = deux aliments,
  forme réellement consommée dans le nom (« thé infusé nature », « pâtes cuites », « Coca-Cola zéro »),
  poids de référence (morceau de sucre 5,5 g, tasse 250 ml, canette 330 ml…).

**Ce qui change par rapport aux décisions précédentes.** Le modèle donne désormais une estimation de
glucides — **pour contrôler**, jamais pour écrire seule : la valeur retenue reste celle d'une fiche,
sauf quand le diététicien la remplace explicitement, ligne affichée « estimation du diététicien ».

**Ce qui ne change pas.** Rien n'est jamais confirmé dans CamAPS à la place de l'utilisateur ; une ligne non
chiffrée n'est jamais comptée zéro.

**Coût.** Une relecture par repas (deux au plus), quelques centièmes de centime ; 2 à 4 s de plus.

## 44. Un aliment de base ne part pas sur Internet, et les aliments se cherchent en parallèle

**Contexte.** Essai du 29/10/2026 après la décision 43 : 28 s de silence entre la phrase et la première
question, puis près de 2 minutes avant la fin. Mesures sur l'API : une recherche sur Internet coûte
**11 s avec Mistral Large** (6,6 s avec Medium), une question simple 0,5 à 1 s. Le repas « thé + trois
sucres » en lançait trois, l'une après l'autre (thé introuvable hors ligne, poids d'un morceau de
sucre, puis « thé infusé nature Lipton » après la réponse).

**Décision.**
1. La conversation rend une **marque** (`brand`) seulement quand elle change les glucides (biscuits,
   céréales, sodas, plats préparés). Un aliment **sans marque** (thé, sucre, pâtes cuites, fruit) est
   cherché dans la base locale, puis chiffré directement par la valeur de référence du modèle (1 s) :
   ni Open Food Facts en ligne, ni Internet. La relecture (décision 43) le contrôle comme le reste.
2. Le poids d'une pièce d'un aliment sans marque (morceau de sucre, tranche de pain) vient de
   l'estimation de la conversation, jamais d'Internet.
3. Les aliments d'un repas se résolvent **en parallèle** (mémoires de séance synchronisées).
4. La recherche sur Internet passe à `mistral-medium-latest` (`MISTRAL_WEB_MODEL`).
5. Au-delà de 3 s de calcul, « Je calcule les glucides. » — une fois par échange, dite jusqu'au bout
   avant la question suivante.

**Objectif.** Moins de 10 s entre la phrase et la réponse de l'enceinte pour un repas courant.

## 45. Deux destinations pour l'import Glooko : Nightscout classique ou Nightscout for CamAPS FX

**Contexte.** Le fork « Journée CamAPS » (P7, `NightscoutCamAPS\projet\p7-adaptation-importateur.md`)
montre que l'ancien chemin `GlookoToNightscoutMapper` → `GlookoImporter` est incompatible avec son
contrat (glycémies Glooko dans `entries`, `treatments` qui mettent les alarmes en sourdine et entrent
dans IOB/COB, profil écrit, « la dernière ligne gagne »…) et que l'appli doit **transporter** le CSV,
le serveur l'interprétant (D-10 / ADR-006). L'utilisateur veut pourtant garder un chemin pour un Nightscout
ordinaire.

**Décision (l'utilisateur, 29/10/2026).** Un réglage « Envoyer les données de Glooko vers » :
1. **Nightscout classique** (défaut, comportement d'avant) : la conversion reste dans l'appli.
2. **Nightscout for CamAPS FX** : le CSV est lu **en octets bruts** (`GlookoCsvSource.readBytes`, ni
   `Reader` ni `String` : BOM et CRLF intacts, sinon le `previewDigest` change) et envoyé par
   `HttpCamapsImportApi` (`POST /api/v1/camaps/imports/preview`, `text/csv`, ≤ 10 Mo), puis confirmé
   (`/imports/commit {previewId, previewDigest}`) par `CamapsGlookoUploader` : simulation = aperçu seul ;
   confirmation perdue (réseau, 500) rejouée telle quelle deux fois au plus ; 409/410 → nouvel aperçu
   une fois ; 400/401/403/404/413/415 → pas de nouvel essai, message clair. Même adresse et même jeton
   que les réglages Nightscout (`api-secret`), jeton de rôle `camaps-uploader` attendu.

**Ce qui ne change pas.** L'import automatique reste **désactivé** par défaut ; le chemin classique n'est
pas modifié ; aucune suppression ni migration des 331 traitements du 26/10. La parité du
`previewDigest` (fichier envoyé depuis l'appli ↔ même fichier envoyé directement) reste à vérifier contre
une instance locale du fork.


## 46. Le mot-clé « miam-miam » fonctionne hors ligne sur le téléphone, en une traite ou en deux temps (et reste distinct d'« Okay Nabu »)

**Contexte.** l'utilisateur souhaite pouvoir lancer l'annonce d'un repas à tout moment en disant simplement
« miam-miam », aussi bien à l'enceinte de la maison (Home Assistant Voice PE) qu'au téléphone (même
écran éteint ou verrouillé), soit en enchaînant toute la phrase (*« Miam-miam, je mange deux pains au
chocolat »*), soit en marquant une pause après *« Miam-miam »* pour que l'assistant réponde *« Je
t'écoute »* et coupe l'enregistrement automatiquement au silence de fin de phrase.

**Décision.** Quatre mécanismes complémentaires (`docs/MIAM-MIAM.md`) :

1. **Détecteur local Vosk à grammaire fermée sur le téléphone (`WakeWordDetector`).** `com.alphacephei:vosk-android:0.3.75`
   tourne en continu à 16 kHz dans `WakeWordService` (`foregroundServiceType="microphone"`), mais avec
   une grammaire JSON réduite à `["miam-miam", "miam miam", "miam", "[unk]"]` et un modèle français
   léger (`vosk-model-small-fr-0.22`, stocké hors de l'APK sous `files/vosk-model-fr` comme la base
   Open Food Facts). Exiger deux syllabes `miam` consécutives (`WakeWordText.containsWakeWord`) écarte
   les faux départs sur un bruit bref.
2. **Même flux micro pour la phrase dite d'une traite.** Dès que « miam-miam » est reconnu, le détecteur
   garde le même `AudioRecord` ouvert pendant une fenêtre de 1,2 s (`FOLLOW_ON_WINDOW_MS`) : si la
   parole continue, il enregistre jusqu'à 1,5 s de silence (`SpeechSilenceTracker`), emballe le PCM en
   WAV et l'envoie directement à la transcription Mistral (`WakeWordTrigger.WithAudio`). Si le silence
   dure 1,2 s après « miam-miam », il émet `WakeWordTrigger.WakeOnly` : l'écran s'ouvre par-dessus le
   verrouillage, dit « Je t'écoute. » et lance `MealAudioRecorder` avec arrêt automatique au silence.
3. **Arbitrage du micro (`WakeWordArbitrator`).** Un seul lecteur micro à la fois : dès que
   `MealAudioRecorder` ou `AndroidSpeechAnswers` ouvre le micro (ou pendant que le téléphone parle après
   un réveil), la veille Vosk relâche `AudioRecord` et ne le reprend qu'une fois l'échange terminé.
4. **Séparation des mots de réveil entre le téléphone et l'enceinte.** Essai réel du 02/11/2026 :
   dire « Okay Nabu, miam-miam… » dans la pièce déclenchait à la fois l'enceinte et le micro du
   téléphone, faisant partir les deux en boucle. « Miam-miam » est donc réservé au téléphone, tandis
   que l'enceinte répond directement à « Okay Nabu, je mange… / je bois… / annonce… » (`assist_repas`).

## 47. Dictée et voix calculées sur le téléphone (Kroko + Piper upmc), Mistral en secours ; réécoute du mot-clé

**Contexte (09/11/2026).**
- Une étude sur le Hammer a montré que la dictée et la voix peuvent se passer du réseau.
- VoiceStudio a été écarté, et Voxtral en local est impossible : son modèle demande un GPU de
  16 Go.
- L'utilisateur a validé les recommandations et choisi la voix Piper « upmc » à l'écoute.
- Le même jour, l'utilisateur a signalé que « miam-miam » se déclenchait devant la télévision.

**Décision.** (`docs/VOIX-LOCALE.md`, `docs/MIAM-MIAM.md` § 3)

1. **Dictée sur le téléphone d'abord.**
   - Kroko est lu par sherpa-onnx 1.13.8 (`LocalTranscriber` → `KrokoSpeech`).
   - Mistral prend le relais si le modèle manque ou ne rend rien.
   - La conversation du repas reste chez Mistral.
2. **Troisième moteur de voix, `PIPER`** (`@PiperEngine` → `PiperTts`).
   - Voix upmc, locutrice 0, `noise_scale` 0,333.
   - La voix Android sert toujours de repli.
3. **Mot-clé.**
   - La grammaire Vosk reçoit des mots leurres.
   - Kroko réécoute les 2,5 s avant le réveil.
   - Résultat sur 168 phrases de télé : 59 fausses alertes avant, 0 après.
4. **Garde-fous mémoire** (incident CamAPS du 09/11).
   - Un modèle léger (68 Mo), chargé une seule fois.
   - Un seul décodage à la fois, deux fils de calcul.
   - Pas de Parakeet en veille.
5. **Distribution.**
   - La bibliothèque n'est embarquée que par `app`, et l'APK est limité à arm64-v8a.
   - Les modèles sont copiés par adb hors de l'APK, comme Vosk et Open Food Facts.

**Reste ouvert.**
- La licence de Kroko n'est pas vérifiée.
- Les réponses du dialogue passent encore par la reconnaissance vocale d'Android.
- L'essai réel avec la voix de l'utilisateur reste à faire.

## 48. Toutes les IA par OpenRouter, avec une seule clé ; un modèle choisi par rôle

**Date :** 10/11/2026. **Décidé par :** l'utilisateur (« d'autres utilisateurs doivent pouvoir utiliser le
produit : nous restons sur une solution à une seule clé, donc OpenRouter »).

**Contexte.** Jusqu'ici, tout passait par Mistral (Large pour le dialogue, Magistral Medium pour la
relecture, Medium pour les photos, Voxtral pour la dictée et la voix). L'utilisateur prépare un usage avec
des lunettes connectées qui photographieront les assiettes de tous les jours : la lecture des
photos devient centrale.

**Banc du 10/11/2026** (scripts `bench/plates.py`, `bench/texts.py`, consignes réelles de l'appli) :
- 14 assiettes **pesées** (Nutrition5k, Google) + 10 plats familiaux français (Wikimedia),
  3 passages pour les meilleurs modèles ; 10 modèles via OpenRouter, coût réel relevé.
- Photos — erreur moyenne de glucides : Gemini 3.1 Flash Lite **11,7 g** (stable), Gemini 3.7
  Flash 11–16 g, Qwen 3.7 Plus 12,5–14 g, Mistral Medium 18–19 g, Mistral Large 28–33 g.
  Gemini Flash Lite : 1,1 s par photo, 0,54 $ les 1 000.
- Texte (cas × 2) : Gemini Flash Lite 24/24 conversations (1,3 s), 20/20 relectures, 34/36
  estimations, mais 14/16 portions (« un tiers » compris comme un tiers du paquet sans demander) ;
  Mistral Medium 3.1 : 24/24, 19/20, 34/36, 16/16.
- Une consigne qui donne la taille de l'assiette et des couverts n'améliore rien (poids
  surestimés de 13 à 23 %) : non retenue.

**Décision.**

| Rôle | Modèle (identifiant OpenRouter) |
|---|---|
| Dialogue du repas, estimations pour 100 g, photos | `google/gemini-3.1-flash-lite` |
| Relecture des glucides | `mistralai/mistral-medium-3.1` (autre famille que celui qui chiffre) |
| Portions dites | `mistralai/mistral-medium-3.1` |
| Dictée de secours (si Kroko manque) | `mistralai/voxtral-small-24b-2507` (son en `input_audio`) |
| Recherche web (rare) | `google/gemini-3.1-flash-lite` + outil `web` d'OpenRouter |

- La clé OpenRouter est rangée au Keystore (`OpenRouterSecretStore`), saisie dans Réglages →
  Intelligence artificielle, avec un bouton « Vérifier la clé » (crédit restant).
- La clé Mistral devient **facultative** : elle ne sert plus qu'à la voix Voxtral.
- Coût estimé : ≈ 0,0015 $ par échange (≈ 0,0047 $ avant) ; ≈ 3 s de calcul (≈ 5 s avant).

**Écarté.**
- Unlid (Qwen 3.8 « abliterated ») : bon sur les photos, mais seconde clé, revendeur de modèles
  débridés, plus lent et moins juste en texte (frites estimées à 15,5 g/100 g au lieu de 33).
- Voix par OpenRouter : seules les voix GPT Audio existent, et ce sont des modèles de dialogue —
  ils ont répondu à l'annonce au lieu de la lire, ou en ont changé les mots. Inacceptable pour
  une annonce de glucides. La voix reste Piper, Android, ou Voxtral avec une clé Mistral.

**Reste ouvert.**
- Essai réel sur le Hammer avec la clé OpenRouter de l'utilisateur.
- Les modèles Kroko et Piper ne sont pas dans l'APK : un nouvel utilisateur dicte par la
  transcription de secours (OpenRouter) tant qu'il ne les a pas installés.

## 49. Portions en images : banque embarquée (Nutrition5k, CC BY 4.0) + paquets importés

**Date :** 10/11/2026. **Décidé par :** l'utilisateur (« une partie embarquée hors ligne, triée selon les
habitudes françaises, et l'API quand on ne trouve pas »).

- Principe repris des applis de comptage (l'image change avec le poids), **sans rien copier**
  d'une autre appli (refus de reprendre ou de redessiner leurs photos : droit d'auteur).
- Base libre de 33 aliments / 91 photos pesées tirées de Nutrition5k, vérifiées une à une ; format
  d'échange « miam-miam-portions » v1 (import/export .zip), toutes les valeurs nutritionnelles
  conservées (sucres, fibres, IG, charge glycémique, cuisson) pour le futur calcul « digestion lente ».
- Ordre de recherche : paquets de l'utilisateur → base embarquée → Open Food Facts → modèle.
- « Cuit ou à cuire ? » demandé pour les aliments dont le poids change à la cuisson.
- Banc permanent sur le test officiel de Nutrition5k (150 assiettes) : 9,8 g d'erreur moyenne ;
  photos de référence jointes à la lecture : 9,4 g, non activées par défaut.
- Écarté : le modèle embarqué de « nutritrack » (entraîné sur des assiettes synthétiques, sans licence).

Détails : `docs/PORTIONS.md`.

