# Fork notes: AI assistant and Auto-tag

This is a fork of [stefan-niedermann/nextcloud-deck](https://github.com/stefan-niedermann/nextcloud-deck)
(GPL-3.0-or-later). It adds two board actions, **AI assistant** and **Auto-tag**. Both call endpoints that only
a forked Deck *server* app provides. All AI work happens on the server; the app sends three plain HTTP requests
and contains no AI SDK, model API key or prompt.

On a stock Deck server nothing changes: the new menu entries stay hidden.

## What changed and where

Everything new lives in one package, so upstream rebases mostly touch files upstream never edits.

| Where | What |
|---|---|
| `app/src/main/java/it/niedermann/nextcloud/deck/forkai/` | All fork code (new) |
| `forkai/ForkAiApi.java`, `forkai/model/*` | Retrofit interface and response models |
| `forkai/ForkAiCapability.java`, `ForkAiCapabilityStore.java` | Feature detection, remembered per account in its own SharedPreferences file (no Room migration) |
| `forkai/ForkAiRepository.java` | Runs the requests: own thread, exactly one attempt, sync before and after |
| `forkai/ForkAiError.java` | Turns status codes and SSO error bodies into user-facing messages |
| `forkai/ForkAiMenu.java` | Adds and gates the two menu entries |
| `forkai/AssistantDialogFragment.java`, `AutoTagDialogFragment.java`, `ForkAiViewModel.java`, `ForkAiState.java`, `ForkAiResultsRenderer.java`, `ForkAiCardOpener.java` | The dialogs |
| `app/src/main/res/values/strings_fork_ai.xml` | All new strings (English only); a separate file so `strings.xml` never conflicts |
| `app/src/main/res/layout/*fork_ai*.xml`, `res/menu/fork_ai_menu.xml`, `res/color/fork_ai_text_*.xml` | New layouts, menu, colors |
| `app/src/test/java/.../forkai/`, `app/src/test/resources/forkai/` | Unit tests and JSON fixtures |

Hooks in upstream files. The Java and Gradle ones carry a `// fork` comment (`git grep -n "// fork"`); the XML value changes are only listed here:

| File | Hook |
|---|---|
| `model/ocs/Capabilities.java` | `forkAi` field with getter/setter |
| `remote/api/JsonToEntityParser.java` | One line in `parseCapabilities` reads `deck.forkAi` |
| `repository/SyncRepository.java` | One line in `refreshCapabilities` stores the value per account |
| `ui/main/MainActivity.java` | Creates `ForkAiMenu`, refreshes its visibility before the ⋮ list menu opens, passes the current board, dispatches clicks |
| `app/build.gradle` | `applicationIdSuffix ".ai"` in `defaultConfig` |
| `res/values/strings.xml` | `app_name` "Nextcloud Deck AI", `app_name_short` "Deck AI" |
| `res/xml/shortcuts.xml` in `main`, `dev`, `play` | `targetPackage` follows the new application ID |

## Own application ID

`defaultConfig` has `applicationIdSuffix ".ai"`. Flavor suffixes are appended after it:

| Flavor | Application ID |
|---|---|
| `fdroid` | `it.niedermann.nextcloud.deck.ai` |
| `play` | `it.niedermann.nextcloud.deck.ai.play` |
| `dev` | `it.niedermann.nextcloud.deck.ai.dev` |

The official builds are `it.niedermann.nextcloud.deck` (F-Droid) and `it.niedermann.nextcloud.deck.play`, so the
fork installs next to them, and store updates never touch it. The Java namespace stays `it.niedermann.nextcloud.deck`.
The only manifest authority already uses `${applicationId}`.

Single sign-on is unaffected: the Nextcloud Files app keys its grants by package name, so the fork simply asks
once for access to the account.

## Server API used by the app

All three routes sit next to Deck's normal REST API (`/index.php/apps/deck/api/v1.0/...`; `v1.1` works too). They go
through the same SSO transport as every other Deck call. No CSRF token is needed. `{boardId}` is the **remote** board
ID (`Board#getId()`).

| Request | Permission | Success body |
|---|---|---|
| `POST boards/{boardId}/assistant` with JSON `{"message": "..."}` | edit | `{summary, results: [{type, ok, detail, cardId?}], notes: [string]}` |
| `GET boards/{boardId}/autotag` | read | `{untagged, labels}` (numbers) |
| `POST boards/{boardId}/autotag` (no body) | edit | `{summary, results: [{cardId, ok, detail}], notes: [string]}` |

- **Assistant message:** after trimming it must not be empty, and it may hold at most 20,000 characters (Unicode
  code points). The dialog checks the same rule before sending.
- **Assistant results:** `type` is one of `create_card`, `append_checklist`, `edit_card`, `move_card`, `add_labels`,
  `archive_card`. `ok: false` means that action failed, and such entries carry no `cardId`. A successful entry can
  also have `cardId: null`.
- **Auto-tag results:** `ok: false` means "left untagged". That is a normal outcome, not an error.
- **Errors** come back as JSON `{status, message}`, and the dialog shows `message` as is:
  - 400: AI not configured, invalid message, or the board has no labels
  - 403: no access
  - 429: rate limit, per user: 30 assistant runs and 20 auto-tag runs per 10 minutes. Nextcloud's rate limiter
    answers with an empty body, so the app shows its own text here.
  - 500: the server's AI call failed after its own retries
- **Feature detection:** `ocs.data.capabilities.deck.forkAi = {version: 1, configured: bool}`. If the key is missing,
  the entries are hidden. With `configured: false` they show disabled, with the hint "(not set up on the server)".

### Errors through SSO

The Files app does the HTTP request. A non-2xx body reaches the app wrapped as
`java.lang.IllegalStateException: {"status":...,"message":"..."}`, so `ForkAiError` extracts the JSON part.
Exceptions inside the Files app (timeouts, broken connections) arrive with the pseudo status 900 and a generic text.

## Timeouts and retries

- **Read timeout:** on the SSO path the Nextcloud Files app performs the request with its own HTTP client and a
  fixed 60-second read timeout. That is `OwnCloudClientFactory.DEFAULT_DATA_TIMEOUT` in nextcloud/android-library,
  used by `InputStreamBinder` in nextcloud/android. `NextcloudRequest` has no timeout field, so Deck cannot raise
  the timeout for single requests.
  - Most runs take 2–30 s and are fine.
  - A run that takes longer fails on the phone after about 60 s while the server keeps going. The app reports a
    timeout when a network failure arrives after ≥ 50 s, because the SSO library reports timeouts and refused
    connections with the same exception.
  - In that case the dialog says the request **may still have been carried out**, and it refreshes the board.
  - A real fix needs a server change, see below.
- **No automatic retries:** the POSTs are not idempotent, and a retry could create duplicate cards.
  - `ForkAiRepository` runs them on its own single thread with one `Call.execute()`. It deliberately avoids
    `RequestHelper` and the shared executor, because that executor re-submits failed tasks up to five times.
  - Only one AI run can be in flight in the whole app at a time.
  - Outside the app's control: the Files app's HTTP client (commons-httpclient default retry handler) may resend a
    request when the server closed the connection before sending any response.
- **Sync around a run:**
  - Before a POST, the app checks the local DB for pending changes (boards, lists, cards, labels, label
    assignments). If there are any, it runs the normal full sync first and waits for it (at most 90 s). If that
    sync fails, the run still happens and the result carries a note.
  - After a successful run, or when the outcome is unknown, it starts the normal full sync, so the changes appear.

## Behaviour summary

- **Where:** the two entries sit in the board's ⋮ menu next to the list tabs. They appear when all of these hold:
  - the board exists on the server (has a remote ID)
  - the user has edit permission
  - the board isn't archived
  - the server advertises `forkAi`
  - the device is online, as judged by the app's own connectivity check, which honours "sync only on Wi-Fi"

  The web app's "archived view" condition has no Android counterpart: archived cards and archived boards have
  their own screens.
- **AI assistant dialog:**
  - multi-line input with a 20,000-character counter
  - Send stays disabled while the message is blank or too long, and while a run is in flight
  - shows progress, then the summary, ✓/✗ entries and notes
  - the input is cleared after a successful run
  - tapping an entry with a card ID opens that card once it has synced; otherwise a toast says to wait
- **Auto-tag dialog:**
  - loads the untagged count when it opens
  - "Tag untagged cards" is enabled only when there are untagged cards and labels, and only once per dialog
  - results show ✓, or a neutral "–" for cards that stayed untagged

## Build and install

Requirements: JDK 17 or newer and an Android SDK with platform 37 (`compileSdk 37`, AGP 9.3, Gradle 9.5 via the
wrapper). Dependencies come from Google Maven, Maven Central and JitPack.

```bash
./gradlew :app:assembleFdroidDebug
adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk

# unit tests of the fork
./gradlew :app:testFdroidDebugUnitTest --tests 'it.niedermann.nextcloud.deck.forkai.*'
```

The debug build is signed with the build machine's debug key. Updates must come from the same machine, or uninstall
first. For a long-lived install, sign a release build with your own keystore, and keep the keystore and passwords
out of the repository:

```bash
./gradlew :app:assembleFdroidRelease
apksigner sign --ks /path/to/your.keystore --out deck-ai.apk app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
```

On first start, pick the Nextcloud account and allow "Deck AI" in the Files app's prompt. The new menu entries
appear after the first capabilities fetch (account import, pull-to-refresh, or background sync).

## Rebasing onto upstream

```bash
git remote add upstream https://github.com/stefan-niedermann/nextcloud-deck.git   # once
git fetch upstream
git rebase upstream/master          # or the release tag you want
```

- Conflicts can only happen in the hook lines listed above: the `// fork` lines, `app_name`/`app_name_short` in
  `strings.xml` and the three `shortcuts.xml` files. Keep both sides.
- If upstream renames `parseCapabilities`, `refreshCapabilities`, the list menu in `MainActivity`, or
  `getLocallyChanged*Directly` in the DAOs, move the matching one-line hook.
- If upstream adds an application ID suffix or a new flavor with its own `shortcuts.xml`, add `.ai` to that
  `targetPackage` as well.
- After the rebase, run the fork's unit tests and the smoke test below.

### Smoke test after a rebase

1. Install the build and open a board on the forked server.
2. ⋮ menu: "AI assistant" and "Auto-tag" are there. On a stock server they are not.
3. Send a short assistant request. Results appear, the board refreshes, and tapping a ✓ entry opens its card.
4. Open Auto-tag. The count appears, and "Tag untagged cards" runs once.

## Possible server-side improvements

These are not needed for the current app; they would make it more robust.

- **Asynchronous runs.** Return `202` with a job ID and let the client poll `GET .../jobs/{id}`. Each HTTP call
  would then stay far below the Files app's 60-second read timeout, and a lost connection would never leave the
  outcome unknown.
- **Idempotency key.** Accept a client-generated request ID header and return the stored result when the same ID
  arrives again. A resent request could then never create duplicate cards.
- **JSON body on 429.** Answer the rate limit with `{status: 429, message}` like the other errors.
