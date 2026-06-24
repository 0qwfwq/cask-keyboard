#!/usr/bin/env python3
"""Build the shipped *trigram* language model asset for Cask's autocorrect/prediction.

Companion to `build_bigrams.py`. The shipped bigram prior gives context from the previous word;
this adds a `P(w3 | w1, w2)` prior from the previous *two* words, so cold-start autocorrect and
prediction can use sharper context ("i want to ___", "going to the ___", "thank you for ___").

Source: a raw English text corpus. There is no public precomputed trigram-count file the size of
Norvig's `count_2w`, so we count trigrams ourselves from a downloadable **public-domain** corpus —
by default Norvig's own `big.txt` (https://norvig.com/big.txt, ~6 MB of Project Gutenberg text),
which keeps Cask's data lineage consistent and needs no license. Point `--corpus` at any UTF-8 text
file (e.g. your own chat export) to personalise the shipped prior instead.

Output: `assets/dictionary/trigrams.txt`, lines of `w1<TAB>w2<TAB>w3<TAB>count`, grouped by `(w1,w2)`
with the top-K successors per context, restricted to the in-vocabulary words the keyboard loads and
capped to keep startup/memory bounded. Parsed by `NgramModel`.

Usage:
    python tools/build_trigrams.py            # download big.txt + build with defaults
    python tools/build_trigrams.py --corpus my_text.txt
    python tools/build_trigrams.py --help
"""
import argparse
import os
import re
import sys
import urllib.request
from collections import defaultdict

SRC_URL = "https://norvig.com/big.txt"
HERE = os.path.dirname(os.path.abspath(__file__))
ASSET_DIR = os.path.normpath(os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "dictionary"))
VOCAB_PATH = os.path.join(ASSET_DIR, "en.txt")
OUT_PATH = os.path.join(ASSET_DIR, "trigrams.txt")

TOKEN_RE = re.compile(r"[a-z']+")


def load_vocab(path, limit):
    """Top `limit` words by frequency — exactly the set Dictionary.MAX_WORDS keeps (see build_bigrams)."""
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
    return (word.isalpha() or word == "i") and word != ""


def read_corpus(src):
    if src.startswith("http://") or src.startswith("https://"):
        print(f"Downloading {src} ...", file=sys.stderr)
        req = urllib.request.Request(src, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read().decode("utf-8", "replace")
    print(f"Reading local {src} ...", file=sys.stderr)
    with open(src, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", default=SRC_URL, help="raw text corpus URL or local path (public-domain by default)")
    ap.add_argument("--vocab", default=VOCAB_PATH, help="path to en.txt vocabulary")
    ap.add_argument("--out", default=OUT_PATH, help="output trigrams.txt path")
    ap.add_argument("--vocab-limit", type=int, default=60000, help="must match Dictionary.MAX_WORDS")
    ap.add_argument("--per-context", type=int, default=8, help="max successors kept per (w1,w2) context")
    ap.add_argument("--max-lines", type=int, default=120000, help="global cap on emitted trigrams")
    ap.add_argument("--min-count", type=int, default=2, help="drop trigrams rarer than this")
    args = ap.parse_args()

    print(f"Loading vocab (top {args.vocab_limit}) from {args.vocab} ...", file=sys.stderr)
    vocab = load_vocab(args.vocab, args.vocab_limit)
    print(f"  vocab size: {len(vocab)}", file=sys.stderr)

    text = read_corpus(args.corpus)
    tokens = TOKEN_RE.findall(text.lower())
    print(f"  corpus tokens: {len(tokens)}", file=sys.stderr)

    # Count trigrams whose three words are all in-vocab and clean. Keyed (w1, w2) -> {w3: count}.
    succ = defaultdict(lambda: defaultdict(int))
    for i in range(2, len(tokens)):
        w1, w2, w3 = tokens[i - 2], tokens[i - 1], tokens[i]
        if not (is_clean(w1) and is_clean(w2) and is_clean(w3)):
            continue
        if w1 not in vocab or w2 not in vocab or w3 not in vocab:
            continue
        succ[(w1, w2)][w3] += 1
    print(f"  {len(succ)} (w1,w2) contexts", file=sys.stderr)

    # Trim each context to its top-K successors above the min-count.
    flat = []
    for (w1, w2), d in succ.items():
        top = sorted(((w3, c) for w3, c in d.items() if c >= args.min_count),
                     key=lambda kv: kv[1], reverse=True)[: args.per_context]
        for w3, c in top:
            flat.append((w1, w2, w3, c))

    # Global cap: keep the highest-count trigrams overall.
    if len(flat) > args.max_lines:
        flat.sort(key=lambda t: t[3], reverse=True)
        flat = flat[: args.max_lines]

    # Emit grouped by (w1,w2), successors heaviest-first (loader/predictor get order for free).
    flat.sort(key=lambda t: (t[0], t[1], -t[3]))
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as out:
        out.write("# w1<TAB>w2<TAB>w3<TAB>count  — shipped English trigram prior (built from a public-domain corpus)\n")
        for w1, w2, w3, c in flat:
            out.write(f"{w1}\t{w2}\t{w3}\t{c}\n")

    size = os.path.getsize(args.out)
    print(f"Wrote {len(flat)} trigrams to {args.out} ({size/1024/1024:.1f} MB)", file=sys.stderr)


if __name__ == "__main__":
    main()
