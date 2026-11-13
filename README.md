# All Blue

Lecteur de documents pour Android, rapide, sans publicité et sans compte. Tout reste sur le téléphone.

Une appli **Thousand Labs** · Page officielle : https://github.com/ThousandLabs/All-Blue

## Ce qu'elle lit

PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), EPUB, fichiers texte (.txt, .csv…) et images.
Les anciens formats Office (.doc, .xls, .ppt) ne sont pas pris en charge : All Blue propose alors de les ouvrir avec une autre appli.

## Fonctions

**Lecture**
- Pages calculées à la demande : ouverture quasi instantanée, même pour un gros PDF.
- Zoom à deux doigts ou double appui, reprise à la dernière page, barre pour sauter à une page.
- Couleurs de lecture : normal, nuit, sépia.
- Mode texte adaptatif : le texte du PDF se remet à la largeur de l'écran, taille réglable.
- Lecture à voix haute hors ligne, avec la voix française du téléphone (vitesse réglable).
- Recherche dans le document, sommaire cliquable, favoris.
- Mode présentation pour les PowerPoint, onglets de feuilles pour les Excel, chapitres pour les EPUB.

**Retouche des PDF**
- Stylo, surligneur, texte, signature (dessinée une fois, réutilisable).
- Masquage définitif d'une information (numéro, adresse…) avant envoi.
- Pages : pivoter, supprimer, réorganiser, extraire.
- Fusion de plusieurs PDF, photos en PDF, compression pour WhatsApp.
- Ajouter ou retirer un mot de passe.
- Copie de secours automatique de l'original avant chaque modification.

**Conversion et partage**
- Word, Excel, PowerPoint et EPUB vers PDF (menu > Exporter en PDF).
- Envoyer seulement certaines pages, ou une page en image.
- Impression.

**Retrouver ses fichiers**
- Récents, favoris, raccourci Documents WhatsApp, Téléchargements, tout le stockage.
- « Tous mes documents » : tous les PDF, Word, Excel… du téléphone, filtrables par type.
- Recherche par nom, et dans le contenu des documents.
- Détection des doublons.

Chaque fonction peut être activée ou désactivée dans Réglages.

## Installation

1. Ouvrez la page **Releases** du dépôt et téléchargez `AllBlue.apk` de la dernière version.
2. Installez-le (autorisez l'installation hors Play Store la première fois).
3. Au premier lancement, touchez **Autoriser** pour donner l'accès aux fichiers.
4. Pour en faire le lecteur par défaut : ouvrez un PDF depuis WhatsApp ou vos fichiers, choisissez **All Blue**, puis **Toujours**. Faites de même une fois pour Word, Excel et PowerPoint.

Les mises à jour suivantes s'installent depuis l'appli (Réglages > Mises à jour).

## Pour la suite

- Retouche directe des fichiers Word et Excel (corriger une phrase, changer une cellule).
- Reconnaissance du texte des documents scannés (OCR).

## Technique

Kotlin + Jetpack Compose. Affichage des PDF par le moteur intégré à Android (PDFium) ; modifications par PDFBox (licence Apache 2.0).
L'APK est compilé et publié automatiquement par GitHub Actions à chaque envoi sur `main`.
