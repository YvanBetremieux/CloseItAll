# CloseItAll — design

Date : 2026-10-12 · Cible : Pixel 8 Pro (Android 14+), non rooté.

## Intention

Yvan perd beaucoup de batterie : Facebook, TikTok et Snapchat consomment chacune ~10 % en
arrière-plan. Il veut une app **qui optimise la batterie** sans elle-même consommer :

1. comprendre **qui** consomme et **pourquoi** ;
2. corriger en un tap (restreindre l'arrière-plan, retirer la localisation en arrière-plan,
   forcer l'arrêt) ;
3. corriger les réglages système qui gaspillent ;
4. automatiser : couper le Bluetooth quand rien n'est connecté, couper la localisation quand
   aucune app GPS n'est utilisée.

Ajout v0.2.0 (demandé par Yvan) : fermeture automatique des apps en arrière-plan. Toutes les N min
(défaut 5), `am force-stop` via Shizuku sur chaque app utilisateur en cours d'exécution qui n'a pas
été à l'écran depuis N min (UsageStats). Protégées : activité affichée (`dumpsys activity
activities`), apps avec service de premier plan, lanceur, clavier, accessibilité, écouteurs de
notifications (montre), téléphone/SMS par défaut, fond d'écran animé, administrateurs, apps
déclarant `USE_EXACT_ALARM` (réveils), Shizuku, CloseItAll, exceptions (messageries par défaut).
Sans accès aux données d'utilisation, rien n'est fermé.

Succès : Yvan installe l'APK depuis GitHub Releases, voit le classement des apps, restreint
Facebook/TikTok/Snapchat en un tap et active les deux automatismes ; l'app consomme < 0,5 %/jour.

## Contraintes techniques

- Sans privilège, une app ne peut ni lire `batterystats`, ni changer le mode batterie d'une
  autre app, ni couper Bluetooth/localisation. Tout passe par **Shizuku** (droits `shell`).
- Shizuku doit être relancé après chaque redémarrage. L'app lui accorde
  `WRITE_SECURE_SETTINGS` (`pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS`)
  pour que l'option « Démarrer au démarrage » de Shizuku fonctionne sans PC. Nécessite le Wi-Fi au
  démarrage. **À vérifier sur l'appareil.**
- Les réglages appliqués (appops, permissions, `settings put`) survivent au redémarrage ; seuls les
  automatismes ont besoin de Shizuku en marche.

## Stack

Kotlin + Jetpack Compose (Material 3), minSdk 26, compile/targetSdk 35, JDK 17, AGP 8.7.
Shizuku API (`dev.rikka.shizuku:api` + `provider`). DataStore Preferences. Pas de Room ni de Hilt :
injection manuelle. `applicationId = io.github.yvanbetremieux.closeitall`.

## Architecture

```
ui/          Compose : Accueil (autorisations), Diagnostic, Système, Automatismes, À propos
shell/       Shell (interface) ← ShizukuShell (UserService, uid shell) ; ShizukuState
diagnostic/  BatteryStatsParser (pur), AppStateReader, AppActions, Advice (pur)
system/      SystemChecks (pur : clé, valeur recommandée, libellé), SystemSettingsRepo
automation/  RuleEngine (pur), WatchService (FGS specialUse), BootReceiver, ActionLog
update/      UpdateChecker (version semver pure + API GitHub), UpdateInstaller
settings/    Prefs (DataStore)
```

### Shell

`interface Shell { suspend fun run(cmd: String): ShellResult }` avec `ShellResult(code, out, err)`.
Implémentation : un `UserService` Shizuku (process `shell`) qui exécute `sh -c`. Tout accès
système privilégié passe par là ; les tests utilisent un faux `Shell`.

### Diagnostic

- Source : `dumpsys batterystats --checkin` (format CSV stable). Lignes utilisées :
  `pwi` (power use item : uid, mAh), `wl` (wakelocks : temps, nombre), `jb` (jobs), `awl`/`wua`
  (alarmes de réveil), `gps`/`sr` si présents, `nt` (réseau), `st` (temps premier plan / arrière-plan
  par état de processus). Si la ligne `pwi` manque, repli sur la section texte
  « Estimated power use » de `dumpsys batterystats`.
- Le parseur est pur et tolérant : les lignes inconnues sont ignorées. Il est testé sur des
  fixtures ; une capture réelle du Pixel remplacera/complétera les fixtures quand disponible.
- UID → apps via `PackageManager.getPackagesForUid`. Les UID système (< 10000) sont regroupés.
- État par app : mode batterie (`cmd appops get <pkg> RUN_ANY_IN_BACKGROUND` → `ignore` =
  Restreinte ; `dumpsys deviceidle whitelist` → Non restreinte ; sinon Optimisée),
  localisation en arrière-plan (`PackageManager.checkPermission`).
- Actions (chacune réversible via « Annuler ») :
  - Restreindre : `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` (+ retrait de la liste blanche
    deviceidle). Annuler : `allow`.
  - Retirer localisation arrière-plan : `pm revoke <pkg> android.permission.ACCESS_BACKGROUND_LOCATION`.
    Annuler : `pm grant`.
  - Forcer l'arrêt : `am force-stop <pkg>`.
- `Advice` (pur) produit une phrase par app à partir des chiffres (seuils sur wakelocks, alarmes,
  GPS, temps d'arrière-plan) et des actions recommandées.

### Réglages système

Table pure de vérifications (`namespace`, clé, valeur recommandée, valeur d'origine pour
« Rétablir », libellé, gravité) :

| Réglage | Clé | Recommandé |
|---|---|---|
| Recherche Bluetooth | `global ble_scan_always_enabled` | 0 |
| Recherche Wi-Fi | `global wifi_scan_always_enabled` | 0 |
| Écran toujours allumé | `secure doze_always_on` | 0 |
| Batterie adaptative | `global adaptive_battery_management_enabled` | 1 |
| Économiseur automatique | `global low_power_trigger_level` | 20 |
| Affichage fluide (suggestion) | `system peak_refresh_rate` | 60 |

Lecture/écriture par `settings get|put` via Shell. La valeur précédente est mémorisée pour
« Rétablir ».

### Automatismes

`RuleEngine` pur : `fun onEvent(state, event, now): Pair<State, List<Command>>`.

- Événements : `BluetoothState(on)`, `BtDevices(count)`, `LocationState(on)`, `Screen(on)`,
  `Foreground(pkg)`, `GpsServiceRunning(bool)`, `Tick`, `ShizukuAvailable(bool)`, `ConfigChanged`.
- Commandes : `DisableBluetooth`, `EnableLocation`, `DisableLocation`, `CheckGpsService`,
  `Log(message)`.
- Bluetooth : allumé + 0 appareil depuis N min (défaut 5) → `DisableBluetooth`. Jamais rallumé.
- Localisation : une app de la liste GPS (défaut Google Maps `com.google.android.apps.maps`,
  Waze `com.waze`) passe au premier plan et localisation éteinte → `EnableLocation`. Localisation
  allumée, aucune app GPS au premier plan depuis N min (défaut 10) → `CheckGpsService` ; si aucune
  app GPS n'a de service de premier plan actif (`dumpsys activity services <pkg>` contient
  `isForeground=true`) → `DisableLocation`, sinon on reporte de N min.
- Rien n'est exécuté si l'app est désactivée ou si Shizuku est absent.

`WatchService` : service de premier plan `specialUse`, notification de faible importance.
Écoute écran (récepteur dynamique), `BluetoothAdapter.ACTION_STATE_CHANGED`,
`BluetoothDevice.ACTION_ACL_CONNECTED/DISCONNECTED`, `LocationManager.MODE_CHANGED_ACTION`.
Tick toutes les 30 s seulement s'il y a un minuteur en cours ; sondage `UsageStatsManager`
toutes les 10 s seulement si l'écran est allumé et la règle localisation active. Démarre au boot
(`BOOT_COMPLETED`) et quand une règle est activée ; s'arrête quand aucune ne l'est.
Commandes système : `svc bluetooth disable`, `cmd location set-location-enabled true|false`.

`ActionLog` : 50 dernières entrées, affichées dans l'onglet Automatismes.

## Socle standard

- Interrupteur global « Activer CloseItAll ».
- Accueil autorisations : Shizuku installé / en marche / autorisé (obligatoire), démarrage auto
  Shizuku (optionnel, bouton qui fait le `pm grant`), accès aux données d'utilisation (pour la
  règle localisation), notifications, Bluetooth « appareils à proximité » (compter les appareils
  connectés). État relu à chaque `ON_RESUME`.
- Mises à jour : `GET api.github.com/repos/YvanBetremieux/CloseItAll/releases/latest`, comparaison
  semver, recherche auto quotidienne (interrupteur), installation auto (interrupteur ; via
  `PackageInstaller` système sinon), bouton « Rechercher les mises à jour », version et date de
  dernière vérification.
- Logs : tag `CloseItAll`, `adb logcat -s CloseItAll` dans le README.

## Erreurs

- Shizuku absent : écrans Diagnostic/Système affichent « Shizuku requis » + bouton ; lien vers
  l'écran Batterie d'Android. Automatismes en pause, notification « Shizuku arrêté ».
- Commande en échec : snackbar courte, détail dans les logs.
- Parsing impossible : message « données indisponibles » + bouton « Copier la sortie brute ».

## Tests

Unitaires (JVM) : `BatteryStatsParser`, `Advice`, `RuleEngine` (horloge simulée),
`SystemChecks`, `VersionComparator`. Checklist manuelle dans le README.
