# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A fork of [stefan-niedermann/nextcloud-deck](https://github.com/stefan-niedermann/nextcloud-deck) (Android client
for the Nextcloud Deck app, GPL-3.0-or-later). The fork adds two board actions, **AI assistant** and **Auto-tag**,
which call endpoints that only a forked Deck *server* provides. All AI work is server-side; the app has no AI SDK,
key or prompt. On a stock server the new menu entries stay hidden.

Read `FORK-NOTES.md` before touching fork code. It holds the server API contract, the list of every hook line in
upstream files, the per-flavor application IDs, the timeout rationale, the phone test plan and the rebase recipe.

- Remote `origin` is the fork (`splinteredlight/nextcloud-deck`); `upstream` is the original project. Work happens
  on `fork/ai`; fork `master` is kept equal to it.
- Application ID is `it.niedermann.nextcloud.deck` + `.ai` + flavor suffix (fdroid: `it.niedermann.nextcloud.deck.ai`),
  so it installs next to the official app. The Java package stays `it.niedermann.nextcloud.deck`.
- All fork code lives in `app/src/main/java/it/niedermann/nextcloud/deck/forkai/` plus `res/values/strings_fork_ai.xml`
  and `*fork_ai*` layouts/menus. The few lines changed in upstream files carry a `// fork` comment:
  `git grep -n "// fork"`. Keep new fork work in that package and mark any new upstream hook the same way, so
  rebasing onto upstream stays cheap.

## Build, test, install

Gradle modules: `:app`, `:cross-tab-drag-and-drop`, `:tab-layout-helper`. Flavors (dimension `version`): `fdroid`,
`dev`, `play`, `pfungstadt`, `mdm`. Java 17, `compileSdk 37`, `minSdk 24`. `local.properties` (git-ignored) must
point `sdk.dir` at the Android SDK.

```bash
./gradlew :app:assembleFdroidDebug                 # APK: app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
./gradlew :app:lintFdroidDebug                     # lint does not abort on error; read the report
./gradlew :app:testFdroidDebugUnitTest             # whole unit suite (JUnit 4 + Robolectric)
./gradlew :app:testFdroidDebugUnitTest --tests 'it.niedermann.nextcloud.deck.forkai.*'      # fork tests only
./gradlew :app:testFdroidDebugUnitTest --tests 'it.niedermann.nextcloud.deck.forkai.ForkAiErrorTest'
./gradlew :app:testFdroidDebugUnitTest --tests '*ForkAiCapabilityTest.parse_*'               # single method pattern
```

CI (`.github/workflows/android.yml`) runs `lintDebug`, `test` and `assembleDev` with `--no-configuration-cache`.

Install on a phone with `adb install -r <apk>`. When reading logs, always filter on the device; an unfiltered
`adb logcat -d` can be gigabytes:

```bash
adb shell "logcat -d -t 3000 --pid=\$(pidof it.niedermann.nextcloud.deck.ai) | grep -E 'DeckLog|AndroidRuntime'"
```

Unit tests run with `includeAndroidResources true` and `robolectric.properties` pins `sdk=24, 35`. Tests that need
Room or SharedPreferences are Robolectric tests; pure parsing tests are plain JUnit.

## Architecture

Three layers, all Java, wired without a DI framework:

**UI (`ui/`)**: Activities/Fragments with ViewBinding, one ViewModel per screen (`ui/<screen>/<Screen>ViewModel`).
ViewModels talk only to the repositories. LiveData chains are built with `ReactiveLiveData` from
`android-commons:reactive-livedata` (`map`, `flatMap`, `tap`, `filter`, `combineWith`, `distinctUntilChanged`);
`tap` steps run on the shared executor, so an exception inside them is retried, not surfaced.

**Repositories (`repository/`)**: `BaseRepository` wraps `DataBaseAdapter` (local only). `SyncRepository extends
BaseRepository` and adds the network side via `ServerAdapter`. Every write goes to Room first with a `DBStatus`
(`LOCAL_EDITED`, `LOCAL_DELETED`, ...) and is pushed later, which is what makes the app offline-first.
`PreferencesRepository` wraps SharedPreferences. `ExecutorServiceProvider` is the single background thread pool
shared by all of them; its `RetryableRunnable` retries a failing task up to 5 times.

**Data (`database/`, `remote/`)**: Room database `DeckDatabase` (schema version 35, migrations in
`database/migration/`) behind `DataBaseAdapter`, which is the only class the repositories call for SQL. Every
synced entity extends `AbstractRemoteEntity` and carries both a `localId` (Room PK, what the UI uses) and `id`
(server ID, what the API uses); confusing the two is the classic bug here. `remote/api/DeckAPI` and
`NextcloudServerAPI` are Retrofit interfaces executed through the Nextcloud Files app's single sign-on
(`ApiProvider`); the Files app performs the HTTP call, which is why timeouts are fixed at 60 s and error bodies
arrive wrapped in `IllegalStateException`. `JsonToEntityParser` hand-parses server JSON into entities.

**Sync (`remote/helpers/`)**: `SyncHelper.doSyncFor(provider)` pulls and `doUpSyncFor(provider)` pushes, one
`AbstractSyncDataProvider` per entity type (`providers/BoardDataProvider`, `CardDataProvider`, ...). Providers
form a tree: a parent provider pulls its children (board → stacks → cards → labels/users/attachments/comments).
`SyncWorker` schedules background sync through WorkManager. Capabilities (`model/ocs/Capabilities`) are refreshed
per account in `SyncRepository.refreshCapabilities` and stored on the `Account` row; the fork hooks in there.

**Current account**: the active account is a `long` in default SharedPreferences
(`R.string.shared_preference_last_account`); `DataBaseAdapter.getCurrentAccountId$()` exposes it as LiveData and
`MainActivity` flat-maps it into the board/stack streams. `-1` means none; the adapter then promotes the first
existing account rather than leaving the app without one.

### Fork pieces

- `ForkAiCapability.parse` reads `capabilities.deck.forkAi` `{version, configured}`; `ForkAiCapabilityStore` keeps
  it per account in its own SharedPreferences file (no Room migration).
- `ForkAiMenu` adds the two entries to the board ⋮ menu and gates them: shown only with edit permission, a
  non-archived board that exists on the server, the capability present, and the device online; disabled with a
  hint when `configured` is false.
- `ForkAiRepository` makes exactly one attempt per run on its own thread, runs a sync before and after, and uses
  the **remote** board ID. `ForkAiError` maps status codes and SSO-wrapped JSON bodies to user text.
- `AssistantDialogFragment` / `AutoTagDialogFragment` + `ForkAiViewModel` + `ForkAiState` drive the dialogs;
  `ForkAiResultsRenderer` draws result rows and `ForkAiCardOpener` opens a card from a result.
- Test fixtures live in `app/src/test/resources/forkai/`.

## Conventions worth knowing

- Strings: upstream `strings.xml` is Transifex-managed; put fork strings in `strings_fork_ai.xml` (English only).
- Shortcuts XML in `main`, `dev` and `play` source sets hard-code `targetPackage`; keep the `.ai` suffix there if
  flavors or suffixes change.
- When rebasing onto `upstream/master`, conflicts can only occur in the `// fork` hook lines, `app_name` strings and
  the three `shortcuts.xml` files. After a rebase run the fork tests and the smoke test in `FORK-NOTES.md`.
- Commits that change fork behaviour should also update `FORK-NOTES.md` (status table, decisions, test plan).
