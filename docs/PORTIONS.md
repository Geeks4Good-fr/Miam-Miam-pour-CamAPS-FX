# Portions en images

**État :** implémenté le 10/11/2026 (décision 49). Essai réel sur le Hammer à faire.

## 1. Principe

Quand une quantité est floue (« un morceau de pain », « une assiette de riz »), Miam-Miam montre
des photos de portions **pesées** de l'aliment ; l'utilisateur touche celle qui ressemble à sa part
et la ligne passe à ce poids. Le principe (l'image change avec le poids choisi) est courant dans
les applis de comptage de glucides ; **les photos et les valeurs sont les nôtres** — rien n'est
repris d'une autre application.

## 2. D'où viennent les aliments, dans l'ordre

1. **Paquets importés** par l'utilisateur (ses propres photos pesées) — prioritaires.
2. **Base libre embarquée** (`app/src/main/assets/portions/base.zip`) : 33 aliments, 91 photos,
   assiettes réelles pesées au gramme du jeu **Nutrition5k** (Google Research, CC BY 4.0), triées
   par fréquence de consommation en France (`frequence_fr`). Photos vérifiées une à une ; celles
   qui montraient un mélange ont été écartées (liste `EXCLUDE` du script de construction).
3. Open Food Facts (base locale puis en ligne), puis l'estimation du modèle (OpenRouter).

La banque n'est consultée que pour un aliment **sans marque** : un produit de marque garde sa fiche.

## 3. Format d'un paquet (« miam-miam-portions », version 1)

Un `.zip` avec `portions.json` et un dossier `photos/` (ou un `portions.json` seul). Modèle commenté :
`modele-portions.zip` (avec `LISEZMOI.md`). Champs principaux par aliment : `id`, `nom`,
`synonymes`, `categorie`, `pour_100g` (glucides obligatoires ; sucres, fibres, amidon, lipides,
protéines, énergie, sel), `index_glycemique`, `digestion` (rapide / moyenne / lente), `cuisson`
(`etat`, `mode`, `rapport_cuit_sur_cru`, `pour_100g_cru`), `portions` (`grammes`, `libelle`,
`photo`), `frequence_fr`, `source`, `photos_licence`. **Les champs inconnus sont conservés**
(base du futur calcul « digestion lente » pour CamAPS).

Contrôles à l'import : nombres plausibles, sucres ≤ glucides, photos présentes, identifiants
uniques ; cohérence d'Atwater (énergie ≈ 4 × glucides + 4 × protéines + 9 × lipides) signalée sans
bloquer. Garde-fou « zip slip ». Tout reste dans le dossier privé de l'appli.

## 4. Cuit ou à cuire

Pour un aliment qui change de poids à la cuisson (riz, pâtes, quinoa…), les valeurs de la banque
sont celles du **cuit**. Si la personne donne un poids sans préciser, le dialogue demande « cuit ou
à cuire ? » ; « riz cru », « à cuire », « sec » prennent les valeurs avant cuisson
(`pour_100g_cru`, ou glucides × rapport cuit/cru).

## 5. Mesures (banc permanent, 10/11/2026)

`bench/n5k_bench.py` : 150 assiettes du jeu de **test officiel** de Nutrition5k (aucune ne sert de
photo de référence), Gemini 3.1 Flash Lite : erreur moyenne de glucides **9,8 g** (32 %), 61 % des
assiettes à ±10 g. Joindre 2–3 photos de référence de la banque : 9,4 g (49 mieux, 39 moins bien) —
gain trop faible pour l'activer par défaut ; à réessayer avec les photos de l'utilisateur.

## 6. Crédits

Photos de la base libre : Nutrition5k — Thames, Q., Karpur, A., Norris, W., Xia, F., Panait, L.,
Weyand, T., Sim, J., « Nutrition5k: Towards Automatic Nutritional Understanding of Generic Food »,
CVPR 2021 — licence CC BY 4.0. Converties en JPEG, sans autre retouche.
