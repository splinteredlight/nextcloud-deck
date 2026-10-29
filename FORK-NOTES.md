# Fork notes: AI assistant and Auto-tag

This is a fork of [stefan-niedermann/nextcloud-deck](https://github.com/stefan-niedermann/nextcloud-deck)
(GPL-3.0-or-later). It adds two board actions, **AI assistant** and **Auto-tag**. Both call endpoints that only
a forked Deck *server* app provides. All AI work happens on the server; the app sends three plain HTTP requests
and contains no AI SDK, model API key or prompt.

On a stock Deck server nothing changes: the new menu entries stay hidden.

## Status of this work

The work is on branch `ccr-bf9f5837-ruh4ej`; rename it to `fork/ai` if you like. It's four commits on top of
upstream `master` at `a7c705a`:

1. `fork: own application ID and app name`
2. `fork: AI assistant and Auto-tag for the forked Deck server`
3. `fork: add FORK-NOTES.md`
4. `fork: notes: verification status, phone test plan, decisions`

Get it locally:

```bash
git fetch origin ccr-bf9f5837-ruh4ej
git checkout -b fork/ai origin/ccr-bf9f5837-ruh4ej
```

**It has not been built with Gradle yet.** The cloud session that wrote it had no access to the Android SDK, Google
Maven or JitPack, so `./gradlew` stopped at dependency resolution. What was checked there instead:

| Check | Result |
|---|---|
| All `forkai` classes compiled with `javac -Xlint:all`, against the Android API jar plus hand-written signature stubs for androidx, Material, SSO and app classes | no errors, no warnings |
| Unit tests that need no Robolectric (`ForkAiErrorTest`, `ForkAiModelParsingTest`, the `parse_*` cases of `ForkAiCapabilityTest`), run with plain JUnit | 32 of 32 pass |
| All new and changed XML well-formed; every `@string`/`@dimen`/`@layout`/`@color` reference resolves; no duplicate string names | ok |
| Diff checked for server hostnames, server-side paths, config keys and prompt text | clean |

Not verified yet, so check these first on your machine:

- the real Gradle build and lint
- ViewBinding/`R` generation, and the real androidx/Material/SSO signatures (the stubs only matched what was read
  from their sources)
- the Robolectric tests: `ForkAiCapabilityTest` `capabilitiesResponse_*` (through the app's real
  `JsonToEntityParser`) and `store_*` (SharedPreferences), plus the existing upstream suite
- everything at runtime: SSO account selection under the new package name, the dialogs, sync before and after a
  run, opening cards

If the build fails, the likely spots are the hook lines in upstream files and the androidx/Material calls in
`ForkAiResultsRenderer`, `AssistantDialogFragment` (`TextInputLayout#setLengthCounter`) and
`ViewCompat.replaceAccessibilityAction`.

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

## Decisions and deviations

**Where the server code differed from the original brief** (the server code won):

- A 429 response has the body `[]`, not `{status, message}`. Nextcloud core's rate limiting middleware answers before
  Deck's exception middleware runs. The app shows its own rate-limit text.
- The web dialog buttons are labelled "Send" (assistant) and "Tag untagged cards" (auto-tag); the app uses the same
  labels.
- Failed assistant entries carry no `cardId`, and successful ones can carry `cardId: null`.
- A 401 (not logged in) and a 404 (server without the endpoints) are possible too, and the app has messages for them.

**Choices you may want to revisit:**

- **Menu location:** the board's ⋮ list menu, which is the only overflow menu on the board screen. It's already
  shown only with edit permission.
- **Offline:** the entries are hidden, not disabled, as the brief said to gate on "online". `configured: false` is
  the only disabled state.
- **Strings:** in `strings_fork_ai.xml`, not `strings.xml`, so upstream string changes never conflict. Moving them
  is a cut and paste.
- **Wording:** short labels match the web UI. Longer hints and the example placeholder are paraphrased rather than
  copied from the server repository.
- **License headers:** new files carry `SPDX-FileCopyrightText: 2026 splinteredlight` and
  `SPDX-License-Identifier: GPL-3.0-or-later`. Upstream files have no headers. Replace the handle with your name if
  you prefer.
- **Pre-sync failure:** if pushing local changes fails or takes longer than 90 s, the run still goes ahead, and the
  result carries a note saying so.

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

## Manual test plan (phone)

1. Install the debug APK. "Deck AI" appears next to the official Deck app, and both open independently.
2. Pick the account and allow access in the Files app prompt. Boards sync.
3. Pull to refresh, then open the ⋮ menu next to the list tabs on a board you can edit. "AI assistant" and
   "Auto-tag" are there. In airplane mode they are missing. On a board you can only read, the ⋮ button itself is
   hidden (upstream behaviour).
4. **AI assistant:** send a short request.
   - Expect "Working on it…", then the summary, ✓/✗ rows and notes.
   - The input is cleared, the board refreshes, and tapping a ✓ row opens its card.
5. **Input rules:** tap Send twice quickly, and only one run starts. Blank text keeps Send disabled. So does text over
   20,000 characters, which also turns the counter red.
6. **Pending local changes:** edit a card in airplane mode, go back online, then run the assistant. "Syncing your
   local changes first…" shows before the run, and the AI sees the edit.
7. **Auto-tag:**
   - The untagged count shows when the dialog opens.
   - "Tag untagged cards" runs once and then stays disabled.
   - Cards left untagged show a grey "–", not a red ✗.
   - On a board where every card has a label, or one without labels, the button is disabled from the start.
8. **Close mid-run:** close the dialog while a run is going and reopen it. It says another AI request is still
   running, and the board refreshes when the first one finishes.
9. **Error paths:**
   - With the server's AI not configured, pull to refresh. The entries show disabled with "(not set up on the
     server)".
   - Hitting the rate limit shows the rate-limit message.
   - A server error shows the server's message.
10. **Long run:** send a request that takes over 60 s. Expect the timeout message saying the request may still have
    been carried out, then a board refresh. Check the board for duplicates before resending.
11. **Stock server:** with an account on a server without the fork, the entries never appear.

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
- **Client-friendly "not configured" message.** The 400 for a missing AI setup is written for the server admin, and
  the app shows it as is. A short message for end users would read better on the phone. The capability already
  tells the app about this case in advance.
