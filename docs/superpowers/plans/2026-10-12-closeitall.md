# CloseItAll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android qui diagnostique la consommation batterie par app, corrige en un tap, ajuste les réglages système et automatise la coupure Bluetooth / localisation, via Shizuku.

**Architecture:** Logique pure (parseur batterystats, conseils, moteur de règles, vérifications système, comparaison de versions) testée en JVM ; tout accès privilégié derrière l'interface `Shell` implémentée par un UserService Shizuku ; UI Compose à onglets ; un service de premier plan léger pour les automatismes.

**Tech Stack:** Kotlin 2.1, AGP 8.7.3, Compose BOM 2024.12.01 + Material 3, Shizuku API 13.1.5, DataStore 1.1, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-12-closeitall-design.md`

## Global Constraints

- `applicationId = io.github.yvanbetremieux.closeitall`, minSdk 26, compile/targetSdk 35, JDK 17.
- `versionCode = GITHUB_RUN_NUMBER ?: 1`, `versionName = GITHUB_REF_NAME.removePrefix("v") ?: "0.0.0-dev"`.
- Aucune commande privilégiée hors de `Shell`. Rien ne s'exécute si l'app est désactivée.
- UI en français. Tag de log `CloseItAll`.
- Identité git gmail ; secrets (keystore) jamais commités.

## Review Focus

1. Sortie batterystats d'une autre version d'Android (lignes inconnues, champs manquants) → le parseur ignore sans planter et renvoie ce qu'il comprend.
2. Shizuku tué pendant que le service tourne → les automatismes passent en pause sans crash, reprennent au retour.
3. Bluetooth reconnecté pendant le délai → le minuteur est annulé, aucune coupure.
4. App GPS en navigation (service de premier plan) quand le délai expire → la localisation reste allumée, reportée.
5. Tag de release non semver ou absence d'asset `.apk` → « aucune mise à jour », pas de crash.

Chaque ligne a un test dans la tâche 2 (1, 3, 4, 5) ; la ligne 2 est couverte par `RuleEngine` (`ShizukuAvailable(false)` ⇒ aucune commande) et la checklist manuelle.

---

### Task 1: Squelette Gradle, CI, app à onglets

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew*`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/.../MainActivity.kt`, `ui/AppScaffold.kt`, `.github/workflows/android.yml`.

- [ ] Copier wrapper Gradle 8.11.1 et conventions de `~/dev/FingerPick`.
- [ ] `app/build.gradle.kts` : Compose, `buildFeatures { compose = true; aidl = true; buildConfig = true }`, signature release via `CLOSEITALL_KEYSTORE`.
- [ ] Workflow CI : test + assembleDebug sur push/PR ; sur tag `v*` assembleRelease signé, `CloseItAll-vX.Y.Z.apk`, `softprops/action-gh-release@v2`.
- [ ] Run: `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
- [ ] Commit « Add Android skeleton and CI ».

### Task 2: Logique pure + tests

**Files:** `update/Version.kt`, `system/SystemChecks.kt`, `automation/RuleEngine.kt`, `diagnostic/BatteryStatsParser.kt`, `diagnostic/Advice.kt` ; tests miroirs sous `app/src/test/...` ; fixture `app/src/test/resources/batterystats_checkin.txt`.

**Interfaces produites :**
- `Version.parse(String): Version?`, `Version : Comparable<Version>`, `ReleaseInfo.pickApk(assets: List<Asset>): Asset?`.
- `SystemCheck(id, namespace, key, recommended, label, explanation, suggestionOnly)`, `SystemChecks.all`, `SystemCheck.isOk(current: String?): Boolean`.
- `RuleConfig(enabled, btEnabled, btDelayMin, locEnabled, locDelayMin, gpsApps: Set<String>)`, `RuleState`, `sealed Event`, `sealed Command`, `RuleEngine.onEvent(config, state, event, nowMs): Pair<RuleState, List<Command>>`, `RuleState.nextDeadline: Long?`.
- `UidStats(uid, mah, wakelockMs, wakelockCount, jobCount, alarmWakeups, gpsMs, fgMs, bgMs, mobileBytes, wifiBytes)`, `BatteryStatsParser.parseCheckin(text): Map<Int, UidStats>`.
- `Advice.forApp(stats, restricted, bgLocation): AppAdvice(summary, recommendRestrict, recommendRevokeLocation)`.

Tests clés :
```kotlin
@Test fun btReconnectCancelsTimer() { /* BtDevices(0) à t0, BtDevices(1) à t0+2min, Tick à t0+6min ⇒ pas de DisableBluetooth */ }
@Test fun gpsServiceRunningPostpones() { /* loc on, quitte Maps, Tick +10min ⇒ CheckGpsService ; GpsServiceRunning(true) ⇒ pas de DisableLocation, nouvelle échéance */ }
@Test fun noShizukuNoCommands() { /* ShizukuAvailable(false) puis Tick après délai ⇒ aucune commande système */ }
@Test fun parserIgnoresUnknownLines() { /* lignes "9,0,l,zzz,..." et champs manquants ⇒ pas d'exception */ }
@Test fun nonSemverTagIsNull() { assertNull(Version.parse("nightly")) }
```
- [ ] Écrire tests → FAIL ; implémenter → PASS (`./gradlew testDebugUnitTest`).
- [ ] Commit « Add pure logic: rules, parser, checks, versions ».

### Task 3: Couche Shizuku

**Files:** `app/src/main/aidl/.../IShellService.aidl`, `shell/ShellService.kt` (UserService), `shell/Shell.kt` (`interface Shell`, `ShellResult`), `shell/ShizukuShell.kt`, `shell/ShizukuState.kt` (StateFlow : NotInstalled / NotRunning / NoPermission / Ready).

- [ ] Manifest : `<provider rikka.shizuku.ShizukuProvider>` + `moe.shizuku.manager.permission.API_V23`.
- [ ] `ShizukuShell.run` lie le UserService à la demande, timeouts 10 s, renvoie `ShellResult(code=-1)` si indisponible.
- [ ] Build OK, commit « Add Shizuku shell layer ».

### Task 4: Diagnostic

**Files:** `diagnostic/DiagnosticRepository.kt` (dumpsys → parse → UID→packages → état), `diagnostic/AppActions.kt`, `ui/DiagnosticScreen.kt`.

- [ ] Liste triée par mAh, détail dépliable (chiffres + `Advice`), boutons Restreindre / Retirer localisation AP / Forcer l'arrêt + snackbar « Annuler ».
- [ ] Sans Shizuku : carte « Shizuku requis » + bouton vers `Intent.ACTION_POWER_USAGE_SUMMARY`.
- [ ] Parsing vide : « données indisponibles » + « Copier la sortie brute ».
- [ ] Commit « Add battery diagnostic screen ».

### Task 5: Réglages système

**Files:** `system/SystemSettingsRepository.kt`, `ui/SystemScreen.kt`.
- [ ] Lecture `settings get`, « Corriger » `settings put` avec mémorisation de l'ancienne valeur (DataStore), « Rétablir ».
- [ ] Commit « Add system settings checks ».

### Task 6: Automatismes

**Files:** `settings/Prefs.kt`, `automation/WatchService.kt`, `automation/BootReceiver.kt`, `automation/ActionLog.kt`, `automation/SystemProbe.kt`, `ui/AutomationScreen.kt`.
- [ ] Service `foregroundServiceType="specialUse"`, récepteurs dynamiques écran / BT / ACL / localisation, tick seulement si `nextDeadline`, sondage UsageStats 10 s si écran allumé et règle loc active.
- [ ] Exécution des `Command` via `Shell` : `svc bluetooth disable`, `cmd location set-location-enabled true|false`, `dumpsys activity services <pkg>`.
- [ ] UI : interrupteurs, délais, liste apps GPS (sélection parmi apps installées), journal.
- [ ] Commit « Add Bluetooth and location automations ».

### Task 7: Accueil autorisations + démarrage auto Shizuku

**Files:** `ui/OnboardingScreen.kt`, `shell/ShizukuAutostart.kt`.
- [ ] Lignes : Shizuku (installer / lancer / autoriser), démarrage auto (`pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS` puis ouvrir Shizuku), données d'utilisation, notifications, appareils à proximité. Refresh `ON_RESUME` + 1 s.
- [ ] Commit « Add permissions onboarding ».

### Task 8: Mises à jour

**Files:** `update/UpdateChecker.kt`, `update/UpdateInstaller.kt`, `ui/AboutScreen.kt`, `res/xml/file_paths.xml`.
- [ ] API GitHub, check quotidien, téléchargement `cacheDir/updates`, installation `PackageInstaller` session (confirmation système).
- [ ] Commit « Add GitHub update checker ».

### Task 9: Docs et release

- [ ] README FR, CLAUDE.md, icône.
- [ ] Keystore `~/dev/keystores/closeitall-release.jks` (mot de passe dans le trousseau), secrets GitHub `CLOSEITALL_*`.
- [ ] Push `main`, CI verte, tag `v0.1.0`, vérifier l'asset APK.
