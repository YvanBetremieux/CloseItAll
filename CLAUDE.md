# CloseItAll

Projet perso de Yvan : identité git **gmail** (`yvan.betremieux@gmail.com`), compte GitHub
`YvanBetremieux`. Jamais d'adresse papernest. **Ne jamais pousser, tagger ni publier une release
sans feu vert explicite** (les apps installées récupèrent la release automatiquement).

## Commandes

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease   # vérifie R8 en local (signé debug sans CLOSEITALL_KEYSTORE)
```

## Pièges

- Tout accès privilégié passe par `shell/Shell` (UserService Shizuku, uid `shell`). Aucune
  commande système ailleurs. Valider les noms de paquets (`AppStateParsing.isValidPackage`)
  avant de les mettre dans une commande.
- `ShellService` est instancié par Shizuku par réflexion : garder les règles `-keep` de
  `app/proguard-rules.pro`.
- Les réponses binder sont limitées à ~1 Mo : utiliser `Shell.read` (pipe) pour les gros dumps.
- `RuleEngine` est pur et piloté par `SystemClock.elapsedRealtime()` ; le service ne pose une
  alarme que si `nextDeadline` n'est pas nul.
- Le format de `dumpsys batterystats` varie selon la version d'Android : le parseur doit rester
  tolérant (lignes inconnues ignorées) et testé sur des fixtures.
- `versionCode` vient de `GITHUB_RUN_NUMBER` : il doit toujours augmenter.
