# La base Open Food Facts sur le téléphone

> État au 26 octobre 2026. Ce document décrit comment la base complète d'Open Food Facts est
> fabriquée sur le PC, copiée sur le téléphone, et utilisée par l'application **avant** toute
> recherche sur Internet.

## Pourquoi

Le premier jet de la dictée cherchait chaque aliment sur l'API publique d'Open Food Facts. Trois
défauts :

1. **Lent** : une requête réseau par aliment, sur un téléphone, au moment où l'on va manger.
2. **Fragile** : sans réseau, plus aucune valeur — alors que le total de glucides est justement
   ce qu'on veut connaître à table, en avion, dans une cave.
3. **Bavard** : envoyer « ce que je vais manger » à un service extérieur à chaque repas n'est pas
   nécessaire quand la donnée peut être embarquée.

La base collaborative complète tient sur le téléphone : **3 065 819 produits** chiffrés en
glucides, 653 Mo. On la pose une fois ; ensuite la recherche est locale et quasi instantanée,
et Internet ne sert plus qu'aux aliments qui n'y sont vraiment pas (plats maison, spécialités
locales), via la recherche web de Mistral.

## Ce que contient le fichier

| Table | Rôle |
| --- | --- |
| `produits` | `code`, `nom`, `nom_norm`, `marque`, `glucides` (aux 100 g), `ml` (1 pour une boisson), `portion` (grammes d'une portion), `portion_libelle` (« 2 biscuits (25 g) »), `paquet` (grammes du paquet), `scans` (popularité), `fr` (1 si vendu en France) |
| `recherche` | index plein texte FTS4 sur `nom_norm` ; le code-barres y est stocké mais **non indexé** (`notindexed=code`), ce qui allège l'index de moitié |

Les trois colonnes de portion sont ce qui permet de convertir « deux biscuits » ou « un tiers du
paquet » en grammes : **1 316 022 produits** donnent le poids d'une portion, **843 695** le poids
du paquet, et 1 325 502 écrivent un libellé de portion. Ces chiffres sont ceux du fichier installé
(684 322 816 octets), relevés par `count(*)` ; ils bougeront à chaque reconstruction de la base.
Elles viennent de `serving_size` (texte), `serving_quantity` (nombre) et `quantity`
(contenance du paquet) de l'export Open Food Facts. Un libellé qui commence par un poids
(« 20 g, un biscuit ») ne compte pas comme un nombre d'articles : c'est `ServingText` qui tranche,
côté application.

Une base construite **avant** ces colonnes reste lisible : `OffDatabase` interroge
`PRAGMA table_info(produits)` et lit des `NULL` à la place, si bien qu'une mise à jour de l'APK
n'oblige pas à recopier 653 Mo. On perd seulement la conversion des portions.

Seuls les produits **nommés et chiffrés en glucides** sont conservés : un produit sans valeur
n'apporte rien et ne doit pas empêcher la recherche sur Internet. Les noms sont indexés
normalisés (minuscules, sans accent, sans ponctuation) ; la requête subit la même
transformation dans `OffText`, sinon une recherche accentuée ne trouverait rien.

### L'index est rempli par popularité

C'est le point qui décide de la vitesse. Les lignes de `recherche` sont insérées **dans l'ordre
des `scans` décroissants**, donc leur `rowid` suit la popularité. La recherche lit alors les
`2000` premières correspondances (`SCAN_LIMIT`), qui sont les produits les plus connus, et les
départage par nom exact puis par popularité : une lecture de quelques millisecondes.

Sans cet ordre, il fallait trier **toutes** les correspondances avant de garder les vingt
premières. Mesuré sur le PC : « chocolat » ramène 141 393 produits ; 17 s au premier appel,
1,2 s ensuite — inutilisable à table. Avec l'index ordonné et la lecture bornée : 14 à 25 ms,
quel que soit le mot.

### Les mots de mesure ne sont pas cherchés

`OffText` écarte les mesures et les contenants (`grammes`, `ml`, `cuillère`, `verre`, `pot`,
`tranche`…). Sans cela, « 200 grammes de riz basmati cuit » cherchait d'abord « grammes » — huit
lettres, donc le mot le plus long, donc celui sur lequel la recherche se rabattait — et rendait
des purées Picard au lieu du riz demandé.

Si la base ne connaît pas la phrase exacte (« riz basmati cuit » absent, « riz basmati »
présent), `OffText.matchAttempts` retire un mot à la fois **en commençant par la fin** — le
dernier mot est presque toujours le qualificatif — puis se rabat sur le mot le plus long, en
cinq essais au plus.

Les boissons sont chiffrées aux 100 ml par Open Food Facts ; la base leur donne aussi la valeur
aux 100 g (100 ml de boisson ≈ 100 g à 2 % près) pour qu'un verre saisi en grammes se calcule.

## Fabriquer la base (PC)

```powershell
# 1. L'export public complet (CSV compressé, ~1,4 Go ; 9 Go décompressé)
curl.exe -L -o "<dossier>\CraftMCP\off\produits.csv.gz" `
  "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz"

# 2. La base SQLite (≈ 7 min, 626 Mo)
py "<dossier>\CraftMCP\off\construire_base.py"
```

Le script est dans `<dossier>\CraftMCP\off\construire_base.py` (hors dépôt : il ne sert
qu'à la fabrication). Il lit le TSV en flux, garde les produits utiles et écrit
`<dossier>\CraftMCP\off\off-base.db`.

## Copier la base sur le téléphone

```powershell
$pkg = "com.najah.mealannouncer.debug"   # com.najah.mealannouncer pour la version release
adb -s <série> shell mkdir -p "/sdcard/Android/data/$pkg/files"
adb -s <série> push "<dossier>\CraftMCP\off\off-base.db" `
  "/sdcard/Android/data/$pkg/files/off-base.db"
```

L'application lit le fichier en lecture seule depuis son dossier de fichiers externe
(`Android/data/<paquet>/files/off-base.db`). Elle ne le télécharge pas : la copie est
volontaire, faite quand on veut rafraîchir les données.

Mesures sur le PC, base de 653 Mo : **30 à 45 ms** par recherche à chaud, 300 à 450 ms au tout
premier appel (lecture du disque). Sur le téléphone, compter quelques centaines de millisecondes
la première fois ; c'est pour cela que l'annonce vocale n'attend jamais la recherche.

> Une base construite avant le classement par popularité ne convient plus : il faut la
> reconstruire (`construire_base.py`) puis la recopier, sinon la recherche lit 2000 lignes au
> hasard et rend des produits peu connus.

Écran de contrôle : en haut de l'écran **Repas dicté**, une ligne indique
« Base locale Open Food Facts : N produits ». Si elle affiche « absente », la copie n'a pas été
faite (ou pas au bon endroit) et chaque aliment repart sur Internet.

## Ordre de recherche d'un aliment

1. **Cache de l'application** (produits déjà vus, favoris vérifiés) — instantané, local.
2. **Base Open Food Facts du téléphone** — local, ~3,1 M de produits, portions comprises.
3. **API Open Food Facts** — si les deux précédents n'ont rien donné (produit récent, absent de
   la copie).
4. **Recherche web de Mistral**, avec l'URL de la source affichée — en dernier recours seulement.

À chaque étape, `FoodNameMatcher` vérifie que le nom du produit trouvé correspond vraiment à
l'aliment demandé : la recherche d'Open Food Facts est floue (interrogée sur « banane », elle
renvoie un fromage blanc), et chiffrer un aliment avec les glucides d'un autre est la faute la
plus grave que cette application puisse commettre.

## Licence

Les données d'Open Food Facts sont sous **Open Database Licence (ODbL)** ; les contenus
individuels sous DbCL, les images sous CC-BY-SA. Toute réutilisation publique doit créditer
« Contains data from Open Food Facts, available under the Open Database License » et partager
à l'identique une base dérivée. L'application affiche la provenance sur chaque ligne trouvée.

L'application Open Food Facts du Play Store ne peut pas servir de source : ses données sont dans
son stockage privé, illisible par une autre application (et l'application n'est pas
débogable). La copie de la base publique est donc la seule voie exploitable — et c'est aussi la
voie recommandée par Open Food Facts pour un usage hors ligne.
