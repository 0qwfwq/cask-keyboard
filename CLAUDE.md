# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Cask is a **custom Android keyboard (IME)**. The keyboard itself is native Kotlin — Android only
lets a keyboard be an `InputMethodService`, so it cannot be built in Flutter. The Flutter/Dart app
(`lib/main.dart`) is **only the setup/host screen** (enable → switch → test field + haptics
settings). Almost all real logic lives under `android/app/src/main/kotlin/com/example/cask/`.

## Commands

```bash
flutter pub get                 # fetch Dart deps
flutter run                     # install + run on connected device/emulator
flutter build apk --debug       # -> build/app/outputs/flutter-apk/app-debug.apk
flutter install                 # reinstall after a native (Kotlin) change
flutter analyze                 # Dart lint (flutter_lints, see analysis_options.yaml)
flutter test                    # Dart tests (none exist yet)
```

There is **no separate Gradle/Kotlin test or lint step** wired up — the Kotlin keyboard is built as
part of the Flutter Android build. Iterating on native code means `flutter install` (or
`flutter run`) to a device; the IME cannot be exercised in a pure unit-test loop here.

Rebuild the shipped bigram language model (the context prior behind prediction + correction):
```bash
python tools/build_bigrams.py            # downloads Norvig count_2w.txt -> assets/dictionary/bigrams.txt
```

Train the optional neural rescorer model:
```bash
pip install "tensorflow>=2.14"
python tools/train_lm.py --corpus my_text.txt --epochs 8   # writes assets/model/{lm.tflite,vocab.txt}
```

## Architecture

### Flutter ⇄ native bridge
`lib/main.dart` and `MainActivity.kt` communicate over a single `MethodChannel`
**`com.example.cask/keyboard`** (the name must match on both sides). Methods: `openImeSettings`,
`showImePicker`, `isKeyboardEnabled`, `isKeyboardSelected`, `getHaptics`, `setHaptics`. The Flutter
side re-polls status on `AppLifecycleState.resumed` (returning from system dialogs). Haptics
settings are persisted in `SharedPreferences` and read by the keyboard service — `lib/main.dart`,
`MainActivity.kt`, and `Haptics.kt` must agree on the prefs keys.

### Keyboard runtime (the IME)
- `CaskKeyboardService.kt` — the `InputMethodService` the OS binds to. **This is the integration
  hub.** It implements `CaskKeyboardView.OnKeyboardActionListener` (`onText`/`onDelete`/`onEnter`/
  `onPickText`/`onPickGif`/`onSuggestionPicked`/`onGestureStart`/`onGesture`), drives the
  `InputConnection`, and owns the autocorrect *policy* (composing region, word-boundary commit,
  backspace-reverts-correction, auto-capitalization, `i`→`I`, double-space→`. `). It decides when
  correction is disabled (`isCorrectableField` — password/email/URL and non-text fields; it
  deliberately *ignores* `TYPE_TEXT_FLAG_NO_SUGGESTIONS` so apps like Gemini/Keep still get fixes). A glide-typed
  word is committed into the composing region (best guess) with its alternates in the strip, so the
  normal boundary/suggestion machinery still applies; `onGestureStart` first commits any prior word so
  consecutive swipes chain with an auto-inserted space.
- `CaskKeyboardView.kt` — all keys drawn in code (no XML layout): QWERTY + symbol layers, shift/caps,
  auto-repeat delete, the hold-comma→em-dash pattern (`commaKey()`/`showKeyPreview()`), and
  hold-`?123`→emoji-picker. Also **glide / swipe typing**: a lone finger that starts on a letter and
  slides onto another becomes a gesture (two-thumb typing never triggers it, so fast tapping is
  unaffected). While gliding it draws an accent-coloured fading trail (`dispatchDraw`) and stops
  typing keys; on release it hands the traced path + live letter-key centres to the service. Enabled
  only where correction is (`setGestureTypingEnabled`).
- `CaskTheme.kt` / `AppColors.kt` — adaptive theming; pulls a brand colour from the foreground app's
  icon via androidx.palette.

### Autocorrect engine (`engine/`)
A **noisy-channel model**: it commits `argmax score(candidate) = logP(typed|candidate) [error model]
+ logP(candidate|context) [language model] + neural adjustment`. The pieces:
- `KeyGeometry.kt` — QWERTY proximity → spatial substitution costs.
- `Trie.kt` — vocabulary index; prefix completion + bounded weighted-edit-distance candidate walk.
- `EditModel.kt` — proximity-weighted Damerau–Levenshtein → `logP(typed|candidate)`.
- `LanguageModel.kt` — interpolated unigram + **shipped** English bigram (`NgramModel`) + **personal**
  bi/trigram, with smoothing → `logP(candidate|context)`; also produces next-word predictions.
- `NgramModel.kt` — the **shipped** English bigram prior, loaded from `assets/dictionary/bigrams.txt`
  (built by `tools/build_bigrams.py` from Norvig's `count_2w.txt`). This is what makes prediction and
  context-aware correction work on a *fresh* install; the personal model interpolates on top and wins
  as it learns. Without it, context came only from your own past typing (empty cold-start).
- `PersonalStore.kt` — **all on-device learning**, persisted to `filesDir` (new words, frequencies,
  n-grams, reverted corrections). No network, no telemetry.
- `Rescorer.kt` / `NeuralRescorer.kt` — optional TFLite second pass; **no-op unless** a trained model
  exists in `assets/model/`. Its constants (`SEQ_LEN`, `PAD_ID`, `UNK_ID`) must stay in sync with
  `tools/train_lm.py`.
- `GestureDecoder.kt` — decodes a **glide / swipe** path into ranked words: template-matches the
  traced path against each plausible word's ideal path-through-its-key-centres on a *location* and a
  *shape* channel, pruned to words that start/end near the gesture endpoints, with the language model
  breaking ties. Lives behind `CorrectionEngine.gestureCandidates(...)`.
- `TextFixer.kt` — the tools-row **Fix** action: a one-tap cleanup pass over the whole field (every
  word re-checked through `CorrectionEngine.fixWord` with n-gram context, plus spacing / punctuation /
  capitalization rules). URLs, emails, @handles, numbers, emoticons and likely proper nouns are left
  alone. Runs on a background thread in the service (`onFixText`); fully on-device.
- `CorrectionEngine.kt` — the orchestrator the service talks to. Owns the composing word + context;
  returns strip suggestions, the per-boundary `CommitDecision`, glide-typing candidates, and the
  stateless `fixWord`/`fixText` behind the Fix tool. Built via `load(context)`.

### Emoji / GIF / emoticon picker (`emoji/`)
`EmojiPanel.kt` is the picker UI (3 tabs). Data is parsed from assets at runtime:
`assets/emoji/emoji-test.txt` (every Unicode emoji, Unicode categories), `assets/emoji/emoticons.txt`
(`emoticon<TAB>keywords`). Usage frequency stored on-device in `cask_emoji_usage.json`
(`EmojiUsageStore.kt`). The emoji grid is filtered through `Paint.hasGlyph` so the device only shows
emoji its font can actually draw (ship the newest `emoji-test.txt`; old phones silently omit emoji
they can't render). GIFs use `GiphyClient.kt` — **requires a free GIPHY API key** pasted into
`API_KEY` in `GiphyClient.kt` (Tenor stopped accepting new API clients in Jan 2026); without it the
GIF tab shows a setup message. GIF insertion uses Android rich-content `commitContent`
(`GifInserter.kt`), falling back to clipboard where unsupported.

## Conventions / gotchas

- **Assets that are data, not images:** the dictionary (`assets/dictionary/en.txt`, bare `word` or
  `word<TAB>count`), the bigram model (`assets/dictionary/bigrams.txt`, `w1<TAB>w2<TAB>count`, grouped
  by `w1` heaviest-first), emoji, and emoticon files are parsed at runtime. Swapping in a bigger
  frequency list (e.g. Norvig `count_1w.txt`) just works; regenerate `bigrams.txt` after changing the
  vocab size (`Dictionary.MAX_WORDS` must match `tools/build_bigrams.py --vocab-limit`).
- **Package/app ID is the template default `com.example.cask`.** Renaming for publishing means
  changing `applicationId` + `namespace` in `android/app/build.gradle.kts`, the Kotlin package, and
  the `MethodChannel` name in both `MainActivity.kt` and `lib/main.dart`.
- The IME registration lives in `AndroidManifest.xml` (`<service>` with `BIND_INPUT_METHOD`) +
  `res/xml/method.xml` (input method + subtypes). Adding a language = a new `<subtype>` there.
