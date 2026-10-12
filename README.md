# CloseItAll

Optimiseur de batterie pour Android (pensé pour un Pixel 8 Pro, Android 14+, sans root).

- **Diagnostic** : consommation de chaque app depuis la dernière charge, part en arrière-plan,
  et le *pourquoi* (réveils, alarmes, tâches, GPS, données). Actions en un tap : **Restreindre**
  l'arrière-plan, **retirer la localisation en arrière-plan**, **forcer l'arrêt** (avec « Annuler »).
- **Système** : vérifie et corrige les réglages qui gaspillent (recherche Bluetooth/Wi-Fi, écran
  toujours allumé, batterie adaptative, économiseur automatique, 120 Hz en suggestion).
- **Fermeture des apps en arrière-plan** : toutes les N min (5 par défaut), force l'arrêt des apps
  installées que tu n'as pas utilisées depuis N min. Jamais fermées : l'app à l'écran, le lanceur, le
  clavier, la montre connectée, la musique et la navigation (service de premier plan), les réveils,
  Shizuku, et une liste d'exceptions (messageries cochées par défaut). Une app fermée ne reçoit plus de
  notification tant qu'on ne la rouvre pas.
- **Automatismes** : coupe le Bluetooth quand aucun appareil n'est connecté depuis N min ; allume la
  localisation à l'ouverture d'une app GPS et la coupe N min après (sauf navigation en cours).

Tout ce qui touche au système passe par [Shizuku](https://shizuku.rikka.app/) (droits ADB, sans root).

## Installer sur Android

1. Sur le téléphone, ouvrir <https://github.com/YvanBetremieux/CloseItAll/releases/latest>.
2. Télécharger `CloseItAll-vX.Y.Z.apk`, l'ouvrir, autoriser le navigateur à installer des applis
   inconnues, puis **Installer**. Play Protect peut avertir (app non publiée sur le Play Store) :
   « Installer quand même ».
3. Installer **Shizuku** depuis le Play Store (`moe.shizuku.privileged.api`).

## Premier lancement

CloseItAll s'ouvre sur l'onglet **Réglages** tant que Shizuku n'est pas prêt.

1. **Démarrer Shizuku** : dans Shizuku, « Démarrer via le débogage sans fil ». La première fois,
   il faut activer les Options pour les développeurs (Paramètres → À propos → appuyer 7× sur
   « Numéro de build »), puis appairer avec le code via la notification Shizuku. Wi-Fi requis.
2. Revenir dans CloseItAll → **Shizuku : Autoriser…** → « Toujours autoriser ».
3. **Démarrage automatique de Shizuku** → *Configurer…* : CloseItAll accorde à Shizuku la
   permission `WRITE_SECURE_SETTINGS`, puis ouvre Shizuku : activer **« Démarrer au démarrage »**
   dans ses réglages. Après un redémarrage (en Wi-Fi), Shizuku repart seul.
4. Optionnel selon les automatismes :
   - **Accès aux données d'utilisation** (fermeture des apps : savoir lesquelles ont servi récemment —
     sans lui, rien n'est fermé ; règle localisation : savoir quelle app est à l'écran) ;
   - **Appareils à proximité** (règle Bluetooth : compter les appareils connectés — sans elle, le
     Bluetooth n'est jamais coupé) ;
   - **Notifications** (état du service, alerte « Shizuku arrêté »).

Les réglages appliqués (apps restreintes, localisation retirée, réglages système) restent après un
redémarrage, même si Shizuku est arrêté. Seuls les automatismes ont besoin de Shizuku en marche ;
sinon ils se mettent en pause et la notification propose de relancer Shizuku.

## Conseils

- Facebook, TikTok, Snapchat : ouvrir le Diagnostic, déplier l'app, **Restreindre**. Leurs
  notifications peuvent alors arriver en retard (ou à l'ouverture de l'app).
- Le service d'automatismes affiche une notification permanente discrète (canal « Automatismes ») :
  on peut la masquer dans Paramètres → Notifications sans arrêter le service.

## Mises à jour

Onglet Réglages → *Mises à jour* : recherche automatique quotidienne (au lancement), bouton
« Rechercher les mises à jour… », version et date de la dernière vérification. Avec « Installer
automatiquement » et Shizuku prêt, la mise à jour s'installe sans confirmation ; sinon Android
demande de confirmer. Les nouvelles versions s'installent par-dessus (même clé de signature).

## Logs

```bash
adb logcat -s CloseItAll
```

## Développement

Prérequis : JDK 17 (`brew install openjdk@17`), Android SDK (platform 35) dans `local.properties`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug     # tests + APK debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Structure : `diagnostic/` (parseur `batterystats`, conseils, actions), `system/` (réglages),
`automation/` (moteur de règles pur + service), `shell/` (Shizuku), `update/`, `ui/`.
La logique pure est testée en JVM (`app/src/test`).

Améliorer le parseur avec une vraie capture du téléphone :

```bash
adb shell "dumpsys batterystats -c --charged" | grep -E '^9,[0-9]+,l,' > app/src/test/resources/pixel_checkin.txt
adb shell "dumpsys batterystats --charged" | grep -E '^ *(Capacity: |UID [^ ]+: )' > app/src/test/resources/pixel_estimated.txt
```

## Publier une version

La CI (`.github/workflows/android.yml`) construit et publie l'APK signé sur un tag `v*` :

```bash
git tag v0.2.0 && git push origin v0.2.0
```

Clé de signature : `~/dev/keystores/closeitall-release.jks` (mot de passe dans le trousseau,
entrée « CloseItAll release keystore »), copiée dans les secrets GitHub `CLOSEITALL_*`.
**Ne jamais la perdre** : un APK signé avec une autre clé ne s'installe pas par-dessus.

## Checklist de vérification manuelle

- [ ] Installation depuis la release, premier lancement sur l'onglet Réglages.
- [ ] Shizuku démarré + autorisé → coche verte, onglet Diagnostic chargé.
- [ ] Diagnostic : Facebook/TikTok/Snapchat apparaissent avec un % proche de l'écran Batterie du Pixel.
- [ ] Restreindre une app → Paramètres → Applis → l'app → Batterie affiche « Restreinte » ; Annuler rétablit.
- [ ] Système : « Recherche Bluetooth » → Corriger → vérifié dans Paramètres → Localisation → Services de localisation.
- [ ] Fermeture des apps (délai 1 min) : ouvrir Facebook, revenir à l'accueil → après 1-2 min,
      « Fermées : Facebook » dans le journal ; Paramètres → Applis → Facebook : « Forcer l'arrêt » grisé.
- [ ] Musique Spotify en fond, WhatsApp (exception), clavier, lanceur → jamais fermés.
- [ ] Rester 10 min sur une même app → jamais fermée.
- [ ] Règle Bluetooth (délai 1 min), aucun appareil connecté → Bluetooth coupé, ligne dans le journal.
- [ ] Écouteurs connectés → Bluetooth jamais coupé.
- [ ] Règle localisation (délai 1 min) : ouvrir Maps → localisation allumée ; quitter → coupée après 1 min.
- [ ] Navigation Maps en cours écran éteint → localisation conservée.
- [ ] Arrêter Shizuku → notification « Automatismes en pause » ; le relancer → reprise.
- [ ] Redémarrage en Wi-Fi → Shizuku et le service repartent seuls.
- [ ] « Activer CloseItAll » désactivé → plus aucune action automatique.
- [ ] « Rechercher les mises à jour… » → « CloseItAll est à jour ».
