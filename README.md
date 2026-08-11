# ES-1 Manager

Application Android pour gérer la carte SmartMedia d'un Korg Electribe ES-1 mkII,
via un lecteur de carte USB (OTG).

## Ce que fait l'appli

- **Extrait et joue les samples contenus dans une banque .es1** grâce au
  décodeur open source [es12wav](https://github.com/polluxsynth/es12wav)
  (compilé automatiquement dans l'APK par le workflow GitHub Actions),
  avec export des WAV extraits vers la carte

- Liste les fichiers de la carte : banques .es1, samples .WAV / .AIFF, names.txt
- Signale les samples mal nommés (l'ES-1 exige 00.WAV à 99.WAV)
- Signale si la racine dépasse 100 fichiers (limite de l'ES-1)
- Analyse l'en-tête des WAV et prévient si ce n'est pas du 32000 Hz
  en 8/16 bits (cause de l'erreur Er.4)
- Renommer / supprimer / infos sur chaque fichier

Aucune permission spéciale : l'appli utilise le sélecteur de dossier Android.

## Rappels ES-1 mkII

- Cartes SmartMedia 3,3 V de 4 à 64 Mo, formatées par l'ES-1 (FAT12)
- Samples : WAV ou AIFF, 32000 Hz, 8 ou 16 bits, mono ou stéréo
- Noms : 00 à 99 + extension, à la racine de la carte, 100 fichiers max
- Les banques .es1 contiennent samples + patterns + songs (~4 Mo par banque)

## Compilation automatique

L'APK est compilé par GitHub Actions à chaque push sur `main`.
Après le push : onglet **Actions** → dernier run → **Artifacts** → `KorgManager-APK`.

## Commandes Termux

```bash
# 1. Préparation
pkg update -y && pkg upgrade -y
pkg install -y git unzip
termux-setup-storage

# 2. Dézipper (le zip est dans Téléchargements)
cd ~
unzip ~/storage/downloads/KorgManager.zip
cd KorgManager

# 3. Créer le dépôt local
git init -b main
git config user.name "VotrePseudo"
git config user.email "vous@exemple.com"
git add .
git commit -m "Premier commit - ES-1 Manager"

# 4. Envoyer sur GitHub
# Créez d'abord un dépôt vide "KorgManager" sur github.com (sans README), puis :
git remote add origin https://github.com/VOTRE_PSEUDO/KorgManager.git
git push -u origin main
# Identifiant = votre pseudo, mot de passe = un token (PAT)
# à créer sur github.com : Settings > Developer settings > Personal access tokens
```

### Variante avec GitHub CLI (plus simple)

```bash
pkg install -y gh
gh auth login
gh repo create KorgManager --public --source=. --remote=origin --push
```

## Récupérer l'APK

1. Sur github.com : dépôt → onglet **Actions**
2. Ouvrir le run "Build APK" (vert quand terminé, ~3-5 min)
3. Section **Artifacts** en bas → télécharger `KorgManager-APK`
4. Dézipper, installer `app-debug.apk` (autoriser les sources inconnues)
