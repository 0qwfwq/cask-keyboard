#!/usr/bin/env python3
"""Build the shipped bigram language model asset for Cask's autocorrect/prediction.

Cask's context model was previously learned *only* from the user's own typing, so a fresh
install had no language context at all: next-word prediction fell back to a static filler
list, and autocorrect could not use context to disambiguate. This script ships a real English
bigram prior so both work from first launch (the personal model still layers on top and wins
as it learns).

Source: Peter Norvig's `count_2w.txt` (https://norvig.com/ngrams/), the bigram companion to
`count_1w.txt` which is already shipped as `assets/dictionary/en.txt`. Same corpus, so the two
models are consistent.

Output: `assets/dictionary/bigrams.txt`, lines of `w1<TAB>w2<TAB>count`, grouped by w1 with the
top-K successors per context, restricted to the in-vocabulary words the keyboard actually loads
and capped to keep startup/memory bounded. Format mirrors en.txt so it drops in with no code
changes if you swap corpora.

Usage:
    python tools/build_bigrams.py            # download + build with defaults
    python tools/build_bigrams.py --help
"""
import argparse
import os
import sys
import urllib.request
from collections import defaultdict

SRC_URL = "https://norvig.com/ngrams/count_2w.txt"
HERE = os.path.dirname(os.path.abspath(__file__))
ASSET_DIR = os.path.normpath(os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "dictionary"))
VOCAB_PATH = os.path.join(ASSET_DIR, "en.txt")
OUT_PATH = os.path.join(ASSET_DIR, "bigrams.txt")


def load_vocab(path, limit):
    """The set of words the keyboard keeps (Dictionary loads the top `limit` by frequency).

    en.txt is frequency-ordered `word<TAB>count`, so the first `limit` words are exactly the
    set Dictionary.MAX_WORDS keeps. Restricting bigrams to this vocab means every successor we
    ship is a word that can actually be predicted/corrected to.
    """
    vocab = set()
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            word = line.split("\t", 1)[0].strip().lower()
            if word:
                vocab.add(word)
            if len(vocab) >= limit:
                break
    return vocab


def is_clean(word):
    return word.isalpha() or word == "i"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default=SRC_URL, help="bigram count source URL (or local file path)")
    ap.add_argument("--vocab", default=VOCAB_PATH, help="path to en.txt vocabulary")
    ap.add_argument("--out", default=OUT_PATH, help="output bigrams.txt path")
    ap.add_argument("--vocab-limit", type=int, default=60000, help="must match Dictionary.MAX_WORDS")
    ap.add_argument("--per-context", type=int, default=16, help="max successors kept per first word")
    ap.add_argument("--max-lines", type=int, default=160000, help="global cap on emitted bigrams")
    ap.add_argument("--min-count", type=int, default=2000, help="drop bigrams rarer than this")
    args = ap.parse_args()

    print(f"Loading vocab (top {args.vocab_limit}) from {args.vocab} ...", file=sys.stderr)
    vocab = load_vocab(args.vocab, args.vocab_limit)
    print(f"  vocab size: {len(vocab)}", file=sys.stderr)

    # Stream the source so we never hold the whole 250MB+ file in memory.
    if args.src.startswith("http://") or args.src.startswith("https://"):
        print(f"Downloading {args.src} ...", file=sys.stderr)
        req = urllib.request.Request(args.src, headers={"User-Agent": "Mozilla/5.0"})
        stream = urllib.request.urlopen(req, timeout=120)
        line_iter = (raw.decode("utf-8", "replace") for raw in stream)
    else:
        print(f"Reading local {args.src} ...", file=sys.stderr)
        stream = open(args.src, "r", encoding="utf-8", errors="replace")
        line_iter = stream

    # Per-context successors: w1 -> {w2: count}, accumulated then trimmed to the heaviest K.
    succ = defaultdict(dict)
    read = kept = 0
    for line in line_iter:
        read += 1
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 2:
            continue
        pair, cnt = parts[0], parts[-1]
        try:
            count = int(cnt)
        except ValueError:
            continue
        if count < args.min_count:
            continue
        sp = pair.split(" ")
        if len(sp) != 2:
            continue
        w1, w2 = sp[0].strip().lower(), sp[1].strip().lower()
        if not is_clean(w1) or not is_clean(w2):
            continue
        if w1 not in vocab or w2 not in vocab:
            continue
        if w1 == w2:
            continue
        d = succ[w1]
        # Keep the max if duplicate casing collapsed into the same pair.
        if w2 not in d or count > d[w2]:
            d[w2] = count
        kept += 1
    if hasattr(stream, "close"):
        stream.close()
    print(f"  read {read} lines, {kept} in-vocab bigrams across {len(succ)} contexts", file=sys.stderr)

    # Trim each context to its top-K successors.
    flat = []
    for w1, d in succ.items():
        top = sorted(d.items(), key=lambda kv: kv[1], reverse=True)[: args.per_context]
        for w2, c in top:
            flat.append((w1, w2, c))

    # Global cap: keep the highest-count bigrams overall.
    if len(flat) > args.max_lines:
        flat.sort(key=lambda t: t[2], reverse=True)
        flat = flat[: args.max_lines]

    # Emit grouped by context, successors heaviest-first (so the loader/predictor get order free).
    flat.sort(key=lambda t: (t[0], -t[2]))
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as out:
        out.write("# w1<TAB>w2<TAB>count  — shipped English bigram prior (Norvig count_2w, vocab-filtered)\n")
        for w1, w2, c in flat:
            out.write(f"{w1}\t{w2}\t{c}\n")

    size = os.path.getsize(args.out)
    print(f"Wrote {len(flat)} bigrams to {args.out} ({size/1024/1024:.1f} MB)", file=sys.stderr)


if __name__ == "__main__":
    main()
