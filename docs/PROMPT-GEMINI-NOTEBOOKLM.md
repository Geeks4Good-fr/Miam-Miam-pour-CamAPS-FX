# Faire un manuel visuel et vidéo de Miam-Miam avec Gemini et NotebookLM

Ce document contient des **consignes prêtes à copier-coller** pour que **NotebookLM** (Google)
et **Gemini** produisent, à partir du manuel écrit, un **manuel visuel**, une **vidéo
explicative**, un **podcast** et des **fiches mémo**.

Les sources de référence sont :

- [`docs/MANUEL-UTILISATEUR.md`](MANUEL-UTILISATEUR.md) : le manuel pas à pas (source principale) ;
- [`README.md`](../README.md) : la description complète des fonctions ;
- `docs/images/` et `docs/images/manuel/` : les **captures d'écran** de l'application ;
- `docs/images/logo-miam-miam.png` : le **logo officiel du projet** (fraise souriante dans une
  assiette, entre une fourchette et un couteau, anneau vert, « Miam Miam — calculateur de
  glucides pour CamAPS FX »). C'est le seul logo à utiliser.

> [!CAUTION]
> **Mode Liberty (décision de l'équipe médicale du 24/11/2026).** Tout support produit doit dire,
> sans l'adoucir, que les utilisateurs de la fonction Liberty de CamAPS FX **n'annoncent pas leurs
> repas dans « Ajouter un repas »** et **ne font pas de bolus avec l'onglet couteau-fourchette** ;
> Miam-Miam ne le fait pas non plus pour eux, et ils doivent **se référer à leur diabétologue**
> pour l'utilisation de Liberty. Voir le chapitre 0 du manuel.

---

## 0. Préparer les sources (5 minutes)

1. Ouvrir **notebooklm.google.com** et créer un carnet **« Miam-Miam : manuel »**.
2. Ajouter comme sources :
   - `MANUEL-UTILISATEUR.md` (ou son export PDF **avec les captures**, conseillé : NotebookLM
     voit alors les images) ;
   - `README.md` ;
   - les captures d'écran `docs/images/manuel/*.png`, si l'import d'images est proposé ;
   - le logo `docs/images/logo-miam-miam.png`.
3. **Avant d'ajouter une capture, vérifier qu'elle ne montre aucune donnée personnelle** : nom,
   adresse Nightscout, clé, glycémie réelle, photos de proches. Recadrer ou flouter au besoin.
4. Dans **Paramètres du carnet → Personnaliser**, coller la **consigne commune** ci-dessous.

---

## 1. Consigne commune (à coller dans chaque outil)

```text
Tu prépares des supports pédagogiques pour « Miam-Miam pour CamAPS FX », une application
Android gratuite destinée aux personnes diabétiques de type 1 qui utilisent la boucle fermée
CamAPS FX.

PUBLIC : une personne qui n'a JAMAIS utilisé l'application, qui n'est pas technicienne, parfois
âgée, parfois fatiguée ou stressée par la maladie. Vouvoiement, phrases courtes, un geste par
phrase, aucun jargon (ou expliqué aussitôt avec les mots du lexique du manuel).

SOURCE DE VÉRITÉ : uniquement les sources du carnet. N'invente AUCUNE fonction, aucun bouton,
aucun chiffre. Reprends les libellés des boutons EXACTEMENT comme dans le manuel, entre
guillemets français (« Photographier l'assiette », « Autre photo », « Envoyer à Gemini »,
« Noter dans le Carnet Glooko », « Envoyer à Nightscout »…). Si une information manque, dis
« voir le manuel » plutôt que de deviner.

MESSAGES DE SÉCURITÉ, OBLIGATOIRES ET JAMAIS ADOUCIS :
1. Miam-Miam ne calcule jamais d'insuline et ne donne aucune dose.
2. « Ajouter un repas » dans CamAPS FX informe la boucle fermée : ce n'est PAS un bolus.
3. C'est toujours l'utilisateur qui appuie sur « Confirmer » dans CamAPS FX, après avoir
   vérifié le nombre de grammes.
4. Tant que Gemini a une question en attente, rien n'est envoyé à CamAPS.
5. En cas de doute sur le total : ne pas confirmer.
6. L'application ne remplace pas l'avis de l'équipe soignante.
7. LIBERTY : si la personne utilise la fonction Liberty de CamAPS FX, Miam-Miam n'annonce AUCUN
   repas dans « Ajouter un repas », et la personne non plus : pas d'annonce dans « Ajouter un
   repas », pas de bolus avec l'onglet couteau-fourchette. Pour l'utilisation de Liberty, elle
   se réfère à son diabétologue. Avec Liberty, les messages 3 et 4 ne s'appliquent pas : il n'y
   a rien à confirmer dans CamAPS FX.
Le message 7 est dit au DÉBUT de chaque support (avant l'installation) et rappelé à la fin.
N'explique pas ce qu'est Liberty au-delà du manuel : renvoie au diabétologue.

LOGO : utilise uniquement le logo du projet fourni (fraise souriante dans une assiette, anneau
vert, « Miam Miam — calculateur de glucides pour CamAPS FX »), sans le redessiner ni le déformer.

À NE JAMAIS FAIRE : montrer ou décrire une injection, une seringue, un stylo à insuline, une
dose ; promettre une glycémie parfaite ; présenter l'IA comme infaillible ; afficher le logo
officiel de CamAPS FX, Glooko, Google ou Open Food Facts comme si le projet leur appartenait
(il n'est affilié à aucun d'eux) ; montrer des données de santé réelles.

TON : chaleureux, rassurant, concret, un peu d'humour léger autour de la nourriture
(l'application s'appelle Miam-Miam 🍓), jamais infantilisant.

LANGUE : français, orthographe et accents irréprochables.
```

---

## 2. NotebookLM : la vidéo explicative (« Résumé vidéo »)

**Studio → Résumé vidéo → Personnaliser**, puis coller :

```text
Réalise une vidéo explicative de 6 à 8 minutes : « Miam-Miam en pratique : votre premier repas
en photo ».

Structure imposée (un chapitre = une diapositive titre + 2 à 4 diapositives) :
0. Ouverture (10 s) : le logo du projet en plein écran.
1. Le problème (30 s) : compter les glucides à chaque repas est la corvée n° 1 du diabète
   de type 1 en boucle fermée.
2. Avant tout : utilisez-vous Liberty ? (45 s) : chapitre 0 du manuel. Le schéma « Liberty ?
   Non → CamAPS FX / Oui → rien dans CamAPS FX », le tableau « Vous continuez / Vous ne faites
   plus » et, en grand : « Pour l'utilisation de Liberty, référez-vous à votre diabétologue. »
3. Miam-Miam en une image (45 s) : vous montrez ou vous dites → Gemini compte et pose des
   questions → Miam-Miam remplit « Ajouter un repas » → vous appuyez sur « Confirmer ».
   Reprends le schéma du chapitre 1 du manuel, et précise : « avec Liberty, rien dans CamAPS ».
4. L'installation en 7 étapes (1 min 30) : chapitre 3 du manuel, en montrant la question
   Liberty (« Oui, Liberty » / « Non ») et en insistant sur l'étape ⑥ (paramètres restreints
   puis accessibilité), qui est la plus délicate.
5. Le premier repas en photo (2 min) : chapitre 5, étape par étape, avec la fourchette posée à
   côté de l'assiette, « Autre photo », « Envoyer à Gemini », la carte « Réponse de Gemini »,
   une question de Gemini et la réponse à voix haute.
6. Les produits du commerce et les portions en photos (1 min) : chapitres 7 et 8 ; l'exemple
   réel des biscuits Prince (41,4 g).
7. Confirmer dans CamAPS FX, ou pas avec Liberty (1 min) : chapitre 9 et ses messages de
   sécurité 1 à 5 ; puis l'écran Liberty : bouton « Noter le repas (Liberty : rien dans
   CamAPS) » et la phrase « Mode Liberty : rien dans CamAPS, je note le repas. »
8. Le Journal et les rattrapages (45 s) : chapitre 11.
9. Les 6 conseils photo (30 s) : chapitre 13, sous forme de « à faire / à éviter ».
10. Conclusion (20 s) : « vérifiez toujours le total avant de confirmer » et « Liberty : rien
    dans CamAPS FX, référez-vous à votre diabétologue ».

Utilise les captures d'écran des sources chaque fois qu'un écran est décrit, avec une flèche
ou un cercle sur le bouton dont on parle. Écris à l'écran les libellés exacts des boutons.
Rythme lent : laisse 2 secondes de plus sur chaque geste.
```

> Astuce : produire **une vidéo par chapitre** (2 à 3 minutes chacune) donne des tutoriels plus
> faciles à revoir. Remplacer alors la structure par le seul chapitre voulu.

---

## 3. NotebookLM : le podcast (« Résumé audio »)

**Studio → Résumé audio → Personnaliser**, format **« Discussion approfondie »**, puis coller :

```text
Deux animateurs bienveillants présentent Miam-Miam à une personne diabétique de type 1 qui
vient de recevoir l'application et hésite à s'en servir. Durée : 10 à 12 minutes.

L'un découvre l'application, l'autre l'utilise depuis des semaines. Ils racontent UN repas
complet à la cantine de l'hôpital (soupe, pâtes, yaourt, pain), du déclenchement « Miam-miam »
jusqu'au « Confirmer » dans CamAPS FX, en citant les boutons exacts.

Ils abordent les questions qu'on se pose : « Et si Gemini se trompe ? » (fourchette, questions,
ne pas confirmer en cas de doute), « Ça coûte combien ? » (environ 4 centimes par repas
photographié), « Mes photos partent où ? » (envoyées à Gemini par OpenRouter, pas gardées dans
l'application), « Mon diabétologue verra quoi ? » (mode suivi : Carnet Glooko et Nightscout),
« Ça fait un bolus ? » (non, jamais).

Dès le début, l'un des deux demande : « Et si j'utilise Liberty ? ». Réponse, mot pour mot
dans l'esprit du manuel : avec Liberty, Miam-Miam compte et note le repas mais n'annonce rien
dans « Ajouter un repas », et la personne non plus — ni annonce, ni bolus avec l'onglet
couteau-fourchette ; pour Liberty, on se réfère à son diabétologue. Ils n'expliquent PAS
Liberty eux-mêmes.

Ils répètent clairement les messages de sécurité, sans dramatiser.
```

---

## 4. NotebookLM : fiches mémo et carte mentale

**Guide d'étude** ou **Note** personnalisée :

```text
Produis une fiche mémo d'UNE page à imprimer et coller sur le frigo :
« Miam-Miam en 6 gestes ».
1 fourchette à côté → 2 « Photographier l'assiette » → 3 « Autre photo » ou « Envoyer à
Gemini » → 4 répondre aux questions à voix haute → 5 « oui » → 6 vérifier le nombre et
« Confirmer » dans CamAPS FX.
En bas : les 3 rattrapages du Journal et les 3 messages de sécurité essentiels.
Encadré rouge en haut : « Liberty ? Rien dans « Ajouter un repas », pas de bolus
couteau-fourchette. Demandez à votre diabétologue. » (gestes 5 et 6 : « sans Liberty
seulement »).
Gros caractères, une icône par geste, aucune phrase de plus de 12 mots. Logo du projet en haut.
```

**Carte mentale** : demander « Carte mentale de toutes les fonctions de Miam-Miam, regroupées
en : Liberty (avant tout), Montrer, Calculer, Confirmer, Garder la trace, Régler, Dépanner ».

---

## 5. Gemini (Canvas) : le manuel visuel interactif

Dans **gemini.google.com**, ouvrir **Canvas**, joindre `MANUEL-UTILISATEUR.md` et les
captures, puis coller la **consigne commune** suivie de :

```text
Transforme ce manuel en une page web interactive d'un seul fichier HTML, lisible sur téléphone :
- un sommaire cliquable en haut, une couleur par chapitre (palette aquarelle douce : fraise,
  menthe, crème) ;
- chaque étape = une carte avec son numéro en grand, la capture d'écran correspondante, le
  libellé exact du bouton surligné et une phrase d'explication ;
- les schémas Mermaid du manuel redessinés en illustrations simples (icônes, flèches) ;
- les encadrés « Important », « Attention », « Astuce » du manuel en cartes de couleur
  (rouge, orange, vert) ;
- en tête, une carte rouge « Avant tout : utilisez-vous Liberty ? » (chapitre 0), avec le
  rappel « référez-vous à votre diabétologue » ;
- le logo du projet en en-tête ;
- un mini-quiz de 5 questions à la fin (ex. : « Faut-il appuyer sur Confirmer soi-même ? »,
  « Avec Liberty, annonce-t-on le repas dans « Ajouter un repas » ? » → non) ;
- texte agrandissable, contrastes suffisants (WCAG AA), textes alternatifs sur chaque image.
N'ajoute aucune fonction absente du manuel.
```

Pour une **infographie** (une image verticale à partager) :

```text
Crée une infographie verticale (format 1080 × 1920) « Un repas avec Miam-Miam » : 6 étapes
illustrées de haut en bas, du téléphone qui photographie une assiette avec une fourchette à
côté, jusqu'au doigt qui touche « Confirmer ». Style aquarelle, fraise 🍓 en mascotte,
textes en français, gros caractères. Aucune seringue, aucun stylo à insuline, aucun logo de
marque (seul le logo du projet est permis). Une pastille rouge à côté de l'étape CamAPS :
« Liberty : rien dans CamAPS FX ». En bas : « Miam-Miam ne calcule jamais d'insuline. Vérifiez
toujours le total. Liberty : demandez à votre diabétologue. »
```

---

## 6. Gemini (Veo / Flow) : des vidéos courtes pour les réseaux

Un **storyboard de 45 secondes**, plan par plan, à donner à Veo ou à monter avec les captures
réelles :

```text
Vidéo verticale 9:16, 45 secondes, sous-titres français incrustés, musique douce.
Plan 1 (0-5 s) : une assiette de pâtes et un yaourt sur un plateau de cantine, une fourchette
  posée à côté. Sous-titre : « Combien de glucides ? »
Plan 2 (5-12 s) : une main tient un téléphone Android au-dessus de l'assiette et prend une
  photo. Sous-titre : « Une photo, une fourchette pour l'échelle. »
Plan 3 (12-20 s) : gros plan sur l'écran (capture réelle « Réponse de Gemini ») avec un total
  qui s'affiche. Sous-titre : « Gemini compte et détaille. »
Plan 4 (20-28 s) : la personne répond à voix haute « une seule tranche ». Sous-titre :
  « Il vous pose les bonnes questions. »
Plan 5 (28-38 s) : écran CamAPS FX flouté, un doigt touche « Confirmer ». Sous-titre :
  « Vous vérifiez, vous confirmez. »
Plan 6 (38-45 s) : le logo du projet, texte « Gratuit · Android · Ne calcule jamais
  d'insuline · Liberty : rien dans CamAPS FX, demandez à votre diabétologue ».
Contraintes : aucun visage reconnaissable, aucune donnée de santé lisible, aucune seringue ni
stylo, aucun logo de marque (seul le logo du projet).
```

> Les écrans du téléphone doivent venir des **vraies captures** (`docs/images/manuel/`) : un
> écran inventé par l'IA montrerait des boutons qui n'existent pas.

---

## 7. Relecture avant toute diffusion

Avant de partager un support produit par Gemini ou NotebookLM, vérifier :

- [ ] les **7 messages de sécurité** sont présents et exacts, **le message Liberty au début**
      et à la fin, avec le renvoi au diabétologue ;
- [ ] le **logo** est celui du projet (`docs/images/logo-miam-miam.png`), non déformé ;
- [ ] aucun bouton, aucun chiffre, aucune fonction **inventés** (comparer avec le manuel) ;
- [ ] aucune **donnée personnelle** (nom, glycémie, adresse Nightscout, clé, visage) ;
- [ ] aucun **logo officiel** de CamAPS FX, Glooko, Google ou Open Food Facts présenté comme
      celui du projet ;
- [ ] les **photos de portions** citées portent leur crédit (Geeks4Good, Patrick Blan,
      Alexandre Roschewitz, licence CC BY) ;
- [ ] l'orthographe et les accents.

---

## Annexe : captures d'écran à fournir

Les images attendues par le manuel, dans `docs/images/manuel/` (à prendre sur un téléphone de
test, **sans données réelles**) :

| Fichier | Écran |
| :-- | :-- |
| `01-accueil.png` | écran d'accueil |
| `02-menu.png` | menu latéral ☰ ouvert |
| `03-photo-conseil.png` | écran du repas avec le conseil de la fourchette |
| `04-autre-photo.png` | carte « photo(s) prête(s) : Autre photo / Envoyer à Gemini / Annuler » |
| `05-reponse-gemini.png` | carte « Réponse de Gemini » avec un tableau |
| `06-fiches-off.png` | fiches Open Food Facts comparées, une cochée |
| `07-portions.png` | rangée « Votre portion ressemble à laquelle ? » |
| `08-camaps-ajouter-repas.png` | CamAPS FX, « Ajouter un repas » rempli (glycémie masquée) |
| `09-journal-gemini.png` | Journal, onglet Gemini, une carte ouverte |
| `10-assistant-liberty.png` | assistant d'installation, étape 1 « Votre traitement : fonction Liberty de CamAPS FX » |
| `11-reglage-liberty.png` | Paramètres → Repas & CamAPS, interrupteur Liberty activé et son avertissement |
| `12-total-liberty.png` | total du repas en mode Liberty, bouton « Noter le repas (Liberty : rien dans CamAPS) » |
| `assistant-etapes-1-2.png`, `assistant-etapes-3-4.png` | assistant d'installation (déjà présentes dans `docs/images/`, numérotation d'avant l'étape Liberty : à refaire) |
| `logo-miam-miam.png` | logo du projet (déjà présent dans `docs/images/`) |
