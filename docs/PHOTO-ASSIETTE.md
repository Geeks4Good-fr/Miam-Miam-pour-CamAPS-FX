# Photo d'assiette — décisions et contrat

> État : **construit** (étape 2 de la version 0.8.0) et **éprouvé sur le téléphone** le
> 26/10/2026 pour la galerie ; la prise de vue réelle reste à faire.

## Ce que ça fait

Photographier une assiette, faire lire ce qu'elle contient par l'IA, et **proposer** des lignes
d'aliments avec leurs portions — que l'utilisateur vérifie avant que quoi que ce soit ne compte.

La photo ne remplace pas la parole : elle **pré-remplit** l'écran du repas dicté. Les lignes
produites suivent ensuite exactement le même chemin que les lignes dictées — c'est
`MealCarbPipeline.resolveLines`, la même fonction que pour une phrase, donc il n'existe pas deux
façons d'obtenir un nombre dans l'application : recherche Open Food Facts, portions, total,
écriture dans CamAPS.

## Décisions de l'utilisateur

1. **La photo part chez Mistral** (vision), pour l'analyse.
2. **Le résultat est toujours confirmé par l'utilisateur** : aucune ligne issue d'une photo n'est
   écrite dans CamAPS sans validation explicite.
3. **Deux sources au choix, proposées à l'utilisateur** :
   - l'**appareil photo** du téléphone (`MediaStore.ACTION_IMAGE_CAPTURE`, photo dans le cache de
     l'application, jamais dans la galerie) ;
   - un **fichier photo à importer** du téléphone (`PickVisualMedia`, lecture seule, aucune copie).
   L'utilisateur choisit ; aucune des deux n'est imposée.
4. **Photos de test prises sur Internet** — jamais les photos personnelles de l'utilisateur.

## Ce qui a été construit

### Deux boutons, sous le micro

« Photographier l'assiette » ouvre l'appareil photo ; « Choisir une photo » ouvre la galerie. Les
deux passent par le même traitement : l'écran ne sait pas d'où vient la photo.

### Le fichier temporaire

`PlateImageLoader` (feature:assistant) écrit dans `cacheDir/plate/assiette-<horodatage>.jpg`,
exposé par un `FileProvider` d'autorité `${applicationId}.photos` (`photo_paths.xml` :
`<cache-path name="assiette" path="plate/" />`). Conséquences voulues :

- **aucune autorisation `CAMERA`** n'est demandée : `ACTION_IMAGE_CAPTURE` n'en a pas besoin tant
  que l'application ne filme pas elle-même ;
- la photo **n'apparaît pas dans la galerie** ;
- le fichier est **supprimé dès qu'il a été lu**, dans le `finally` de l'analyse ;
- une photo prise il y a plus d'une heure est un reste d'application interrompue : elle est
  effacée au prochain `newTarget()`.

Une photo **choisie** dans le téléphone n'est jamais copiée ni supprimée : le chargeur ne connaît
que ses propres fichiers (`targets`), donc `forget()` ne peut pas toucher à celle de quelqu'un
d'autre.

### La réduction

`sampleSizeFor(width, height, maxSide)` divise par deux au décodage tant que le côté long reste
au-dessus de 1024 px, puis `Bitmap.createScaledBitmap` ajuste une seule fois. Envoi en JPEG
qualité 80. Une photo de 12 Mpx ne tient ainsi jamais entière en mémoire, et le corps de la
requête reste de l'ordre de 100 à 200 ko.

### Le contrat avec Mistral

Même point d'entrée que le reste (`POST /v1/chat/completions`, `mistral-small-latest`,
`response_format: json_object`, température 0), mais `messages[].content` devient un tableau :
un bloc `{"type":"text"}` et un bloc
`{"type":"image_url","image_url":"data:image/jpeg;base64,…"}` — `image_url` est une chaîne, pas
un objet (vérifié dans la documentation Mistral).

`PlateInterpretation` (data:mistral) fixe le cadre :

- le modèle **nomme** et propose un poids, il ne donne **aucun glucides** ;
- poids de **1 à 3 000 g**, sinon `null` : le modèle préfère ne rien dire que deviner ;
- **huit aliments** au plus, du plus gros au plus petit ;
- un plat composé reste **un seul** aliment (sinon les mêmes glucides seraient comptés deux fois) ;
- noms en français, utilisables pour une recherche (« riz basmati », pas « riz ») ;
- liste vide si la photo ne montre pas de nourriture.

Lecture de la réponse (`PlateInterpretation.parse`) :

| Cas | Traitement |
| --- | --- |
| Nom vide | écarté **et signalé** (`aliment 1`, `aliment 2`…) |
| Nom de plus de 80 caractères | écarté et signalé |
| Poids hors bornes | devient `null`, **l'aliment reste** |
| Poids en texte (« 175,5 ») | lu comme un nombre |
| Plus de huit aliments | les suivants sont signalés, pas coupés net |
| Pas de tableau `foods` | `MistralException(INVALID_RESPONSE)` — l'écran le dit |

### Ce que l'utilisateur voit

Une carte « D'après la photo » : chaque aliment avec sa quantité et ses glucides, ou
« valeur manquante » ; le total ; les aliments écartés ; les avertissements de la recherche ; puis
deux boutons — **« Ajouter ces aliments »** et **« Écarter »**.

Un aliment sans poids estimé arrive avec une quantité vide : il suit le chemin ordinaire de la
question de portion, comme un aliment dicté. Le poids de la photo n'est qu'une proposition de
départ, affichée puis modifiable.

Tant qu'une proposition existe, **rien n'est écrit dans CamAPS** : `canWrite` est faux, et la
confirmation remet l'écran à l'état « prêt » sans total partiel.

### Les cas d'échec, dits en clair

| Cas | Message |
| --- | --- |
| Image illisible (fichier disparu, format inconnu) | « Photo illisible : choisissez-en une autre. » |
| Aucune clé d'API | la même invite que partout ailleurs (`missingKey`) |
| Échec Mistral | le message de `MistralException`, tel quel |
| Aucun aliment reconnu | « La photo ne montre aucun aliment reconnaissable. » |
| Appareil photo absent | l'écran revient à l'état précédent, sans fichier orphelin |

## Éprouvé sur le téléphone (26/10/2026, Hammer Iron 6)

Photos de test prises sur Wikimedia Commons (CC BY-SA 4.0), poussées dans
`/sdcard/Pictures/Assiettes-test/`.

| Ce qui a été fait | Résultat |
| --- | --- |
| « Choisir une photo » → galerie → assiette de poulet, riz et salade | lecture proposée en 7 aliments (poulet rôti, salade de lentilles, pain de seigle, noix, carottes râpées, tomates cerises, persil), tous trouvés dans la base locale, total 67 g |
| La même photo de spaghetti bolognaise | lecture proposée en 1 aliment (« Riz Cantonais (BEN'S) », 250 g, 67,5 g) |
| « Écarter » | la carte disparaît, le repas n'a pas bougé |
| « Ajouter ces aliments » | les lignes rejoignent le repas (identifiants à la suite), le total et le bouton d'écriture dans CamAPS reviennent |
| « Photographier l'assiette » | l'application d'appareil photo du téléphone s'ouvre **sans aucune autorisation demandée** |
| Appareil photo abandonné (retour) | retour à l'écran précédent, aucune erreur, `cache/plate/` vide : le fichier temporaire est bien effacé |

Trois enseignements :

1. **Sans contexte, les noms sont approximatifs.** La même assiette de spaghetti a été nommée
   « riz cantonais ». Le cadre dit pourtant au modèle de nommer en français et d'être prudent ;
   c'est la limite du modèle, pas de l'envoi — deux photos différentes donnent bien deux lectures
   différentes, et les poids, eux, sont restés vraisemblables.
2. **Avec une phrase, la lecture devient juste.** En écrivant « spaghetti bolognaise brocoli » dans
   le champ avant de choisir la photo (le texte part comme contexte), la lecture est devenue
   « Spaghetti Bolognaise (Auchan) 250 g » et « chou-fleur brocoli carotte (Picard) 80 g », total
   35,1 g. **Conseil à retenir dans le mode d'emploi : dire ou écrire le plat avant de photographier.**
3. **Le modèle lit aussi ce qui n'est pas une assiette.** Une capture d'écran choisie par erreur a
   été recopiée telle quelle (les lignes du repas affichées à l'écran). Rien d'anormal — la photo
   est ce que l'utilisateur a choisi — mais cela confirme que la carte doit être relue avant
   d'ajouter.

Un défaut de données trouvé au passage : le libellé de portion « **Un biscuit 20 g** » (l'article
écrit avant le poids, avec « un ») n'était pas lu par `ServingText`, qui ne reconnaissait un compte
d'articles que s'il commençait par un chiffre. Le produit était alors refusé en mode « unité(s) »
(« Le produit donne le poids d'une portion mais pas le nombre d'articles »), alors que la fiche dit
bien ce qu'il faut. Mesure dans la base locale : **1 192 fiches** ont un libellé qui commence par
« un » ou « une ». Décision : `NOMBRE_EN_TETE` accepte désormais « un » et « une » pour un article
(et refuse « 1/2 », 6 324 fiches, où la moitié n'est pas un compte d'articles).

## Ce qui reste ouvert

- **La prise de vue réelle** (photographier une assiette avec l'appareil) : l'ouverture et
  l'abandon sont éprouvés, pas la photo elle-même.
- **La précision des poids** n'est pas mesurable par un test : c'est justement pour cela que la
  proposition est confirmée et modifiable.
- **Plusieurs photos** (assiette + emballage pour lire une étiquette) : non prévu. Le modèle
  actuel accepte une seule image par appel ; l'ajouter demanderait un second champ « photo de
  l'emballage » et un code-barres lu par vision — à décider si le besoin apparaît.
