# Cask Keyboard

A working template for a **custom Android keyboard** (an Input Method Editor / IME) that can
replace the user's default keyboard. The keyboard itself is implemented natively in Kotlin (the
only way Android lets you build a keyboard), and a small Flutter app acts as the setup/host screen.

## How it's put together

A keyboard on Android is a background **`InputMethodService`** — it is *not* a normal screen, so
it can't be built in Flutter/Dart directly. This template splits responsibilities:

| Piece | Where | Role |
|-------|-------|------|
| `CaskKeyboardService` | `android/app/src/main/kotlin/com/example/cask/CaskKeyboardService.kt` | The IME service the OS binds to. Routes key events to the focused text field via `InputConnection`. |
| `CaskKeyboardView` | `android/app/src/main/kotlin/com/example/cask/CaskKeyboardView.kt` | The on-screen keys (built in code: QWERTY + symbols + shift/caps + auto-repeat delete). |
| `res/xml/method.xml` | `android/app/src/main/res/xml/` | Declares the input method + its English (US) subtype. |
| `<service>` entry | `android/app/src/main/AndroidManifest.xml` | Registers the keyboard with `BIND_INPUT_METHOD`. |
| `MainActivity` | `.../com/example/cask/MainActivity.kt` | Flutter host + a platform channel to open IME settings/picker and report status. |
| `lib/main.dart` | Flutter | Setup screen: enable → switch → test field. |

## Run it

```bash
flutter pub get
flutter run            # installs the app on a connected device/emulator
```

Then, on the device:

1. Open the **Cask Keyboard** app.
2. Tap **Open keyboard settings** and toggle **Cask Keyboard** on (you'll see a security
   warning — that's standard for every third-party keyboard).
3. Tap **Choose keyboard** and select **Cask Keyboard**.
4. Tap the test field and start typing. Cask works in every app system-wide.

The setup screen auto-updates its status when you return from the system dialogs.

## Build an installable APK

```bash
flutter build apk --debug      # build\app\outputs\flutter-apk\app-debug.apk
```

## Extending the keyboard

- **Keys/layout:** edit the `letterRows` / `symbolRows` / `symbol2Rows` lists and the row builders
  in `CaskKeyboardView.kt`.
- **Colors & sizing:** `res/values/colors.xml`, the `dp(54)` row height, and `textSize` in
  `CaskKeyboardView.kt`.
- **More languages:** add `<subtype>` entries in `res/xml/method.xml`.
- **Behaviour (haptics, popups, suggestions, swipe):** start from the events in
  `CaskKeyboardService` (`onText` / `onDelete` / `onEnter`).

## Hold-comma → em dash

Tapping the comma still types `,`. **Holding** it (~0.3s) pops up a `—` preview and types an em
dash when you lift your finger — the comma key isn't replaced, the dash is just its hold action. See
`commaKey()` / `showKeyPreview()` in `CaskKeyboardView.kt`. The same pattern can give any key a
hold-alternate.

## The autocorrect / prediction engine

All the language intelligence lives in `android/app/src/main/kotlin/com/example/cask/engine/`. It is
a **noisy-channel** model — it commits the word that maximises

```
score(candidate) = log P(typed | candidate)      # error model: how likely this typo is
                 + log P(candidate | context)     # language model: how likely this word is here
                 + neural adjustment              # optional on-device TFLite rescorer
```

| File | Role |
|------|------|
| `KeyGeometry.kt` | Physical QWERTY layout → **proximity-weighted** substitution costs (g↔h cheap, q↔p expensive). This is what makes the error model spatial. |
| `Trie.kt` | Vocabulary index. Prefix search (completion) + a bounded weighted-edit-distance walk that generates correction candidates in one pass. |
| `EditModel.kt` | Precise proximity-weighted Damerau–Levenshtein (substitution + transposition + doubled-letter) → `log P(typed │ candidate)`. |
| `LanguageModel.kt` | Interpolated unigram/bigram/trigram with smoothing → `log P(candidate │ context)`, plus next-word prediction & completion ranking. |
| `PersonalStore.kt` | All on-device learning, persisted to `filesDir`: your new words, your frequencies, your bigrams/trigrams, and corrections you've reverted. **No network, no telemetry.** |
| `Rescorer.kt` / `NeuralRescorer.kt` | Pluggable second pass. `NeuralRescorer` is real TFLite wiring; it activates when you install a model (see below) and is a no-op otherwise. |
| `CorrectionEngine.kt` | Orchestrator the IME talks to: owns the composing word + context, returns strip suggestions and the per-boundary commit decision. |

**Behaviour** (in `CaskKeyboardService.kt`): letters are typed into a composing region so the engine
can still change them; at each word boundary it applies a **hybrid** policy — silently fix a
high-confidence typo, otherwise just offer suggestions and leave your text alone. A backspace right
after an auto-correction **reverts it and teaches the engine not to repeat it**. Sentence-start
auto-capitalization, `i` → `I`, and double-space → `. ` are handled too. Correction is disabled for
password / numeric / no-suggestion fields.

### Make it smarter

- **Bigger dictionary:** replace `assets/dictionary/en.txt` with a real frequency list (e.g. Norvig's
  `count_1w.txt`). The loader reads bare `word` lines *or* `word<TAB>count` lines, so it drops in.
- **Neural rescorer:** run `python tools/train_lm.py --corpus <your_text.txt>` to produce
  `lm.tflite` + `vocab.txt` in `assets/model/`, then rebuild. The engine picks it up automatically;
  the statistical engine keeps working if it's absent.

## Emoji / GIF / emoticon picker

**Hold the `?123` key** (a quick tap still switches to the symbols layer) to open the picker. Three
tabs across the top:

- **Emojis** — *every* Unicode emoji, organized by Unicode's own categories, parsed from
  `assets/emoji/emoji-test.txt`. Your **top 30 most-used** show under "Recently used".
- **GIFs** — GIPHY trending + search (see setup below).
- **Emoticons** — a large, searchable kaomoji list (`assets/emoji/emoticons.txt`,
  `emoticon<TAB>keywords`). Your **top 10 most-used** show under "Recently used".

Tap the **search box** to reveal the keys and search any tab (emoji by name, emoticon by keyword,
GIFs by query). Emoji/emoticon picks are inserted as text; usage frequency is stored on-device in
`cask_emoji_usage.json`. Code lives in `android/app/src/main/kotlin/com/example/cask/emoji/`.

### Enabling GIFs (GIPHY)

GIFs need a **free** GIPHY API key (Tenor stopped accepting new API clients in Jan 2026):

1. Get a key (a couple of minutes): https://developers.giphy.com/dashboard/ — "Create an App",
   choose **API** (not SDK), copy the key.
2. Paste it into `API_KEY` in
   [`engine/.../emoji/GiphyClient.kt`](android/app/src/main/kotlin/com/example/cask/emoji/GiphyClient.kt)
   (the `GiphyConfig` object).
3. `flutter install`.

Without a key the GIFs tab shows a "set up your key" message; emojis and emoticons work regardless.
GIF *insertion* uses Android rich-content (`commitContent`), which only some apps accept (most
messengers do); where it isn't supported, the GIF link is copied to the clipboard to paste instead.

## Note on the application ID

The package is `com.example.cask`. Change `applicationId`/`namespace` in
`android/app/build.gradle.kts` (and the Kotlin package + channel name) before publishing.
