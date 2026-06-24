#!/usr/bin/env python3
"""
Train the on-device neural language model used by Cask's autocorrect rescorer.

This produces the two files NeuralRescorer.kt expects in `android/app/src/main/assets/model/`:

  * lm.tflite  — next-word LM. Input  : int32 tensor [1, SEQ_LEN] (last SEQ_LEN token ids,
                                        left-padded with 0).
                                Output : float32 tensor [1, vocab] (softmax over the vocabulary).
  * vocab.txt  — one token per line; line index == token id.
                 index 0 == <pad>, index 1 == <unk>  (must match the constants in NeuralRescorer.kt).

The defaults below MUST stay in sync with NeuralRescorer.kt (SEQ_LEN, PAD_ID, UNK_ID).

Usage
-----
    pip install "tensorflow>=2.14"
    python tools/train_lm.py --corpus my_text.txt --epochs 8

Pass a large, natural English corpus as --corpus (your own chat/email export, a public domain
book dump, OpenSubtitles, etc.). The bigger and more "like you" it is, the better the predictions.
The model is intentionally tiny so it stays fast and small on a phone.
"""
import argparse
import os
import re
from collections import Counter

import numpy as np
import tensorflow as tf

# --- Must match NeuralRescorer.kt ------------------------------------------------
SEQ_LEN = 8
PAD_ID = 0
UNK_ID = 1
PAD_TOKEN = "<pad>"
UNK_TOKEN = "<unk>"
# --------------------------------------------------------------------------------

TOKEN_RE = re.compile(r"[a-z']+")


def tokenize(text: str):
    return TOKEN_RE.findall(text.lower())


def build_vocab(tokens, max_vocab: int):
    counts = Counter(tokens)
    most = [w for w, _ in counts.most_common(max_vocab - 2)]
    itos = [PAD_TOKEN, UNK_TOKEN] + most
    stoi = {w: i for i, w in enumerate(itos)}
    return itos, stoi


def make_sequences(tokens, stoi):
    ids = [stoi.get(t, UNK_ID) for t in tokens]
    X, y = [], []
    for i in range(1, len(ids)):
        ctx = ids[max(0, i - SEQ_LEN):i]
        ctx = [PAD_ID] * (SEQ_LEN - len(ctx)) + ctx
        X.append(ctx)
        y.append(ids[i])
    return np.array(X, dtype=np.int32), np.array(y, dtype=np.int32)


def build_model(vocab_size: int, embed: int, units: int):
    inp = tf.keras.Input(shape=(SEQ_LEN,), dtype="int32")
    x = tf.keras.layers.Embedding(vocab_size, embed, mask_zero=True)(inp)
    x = tf.keras.layers.LSTM(units)(x)
    x = tf.keras.layers.Dense(units, activation="relu")(x)
    out = tf.keras.layers.Dense(vocab_size, activation="softmax")(x)
    model = tf.keras.Model(inp, out)
    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, help="UTF-8 text file to train on")
    ap.add_argument("--out-dir", default="android/app/src/main/assets/model")
    ap.add_argument("--max-vocab", type=int, default=20000)
    ap.add_argument("--embed", type=int, default=64)
    ap.add_argument("--units", type=int, default=128)
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=256)
    args = ap.parse_args()

    with open(args.corpus, encoding="utf-8", errors="ignore") as fh:
        text = fh.read()

    tokens = tokenize(text)
    if len(tokens) < 1000:
        raise SystemExit("Corpus too small; give it a real body of text.")

    itos, stoi = build_vocab(tokens, args.max_vocab)
    X, y = make_sequences(tokens, stoi)
    print(f"vocab={len(itos)}  sequences={len(X)}")

    model = build_model(len(itos), args.embed, args.units)
    model.summary()
    model.fit(X, y, batch_size=args.batch, epochs=args.epochs, validation_split=0.05)

    os.makedirs(args.out_dir, exist_ok=True)

    # vocab.txt — index == id.
    with open(os.path.join(args.out_dir, "vocab.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(itos))

    # Convert to TFLite. The int32 input + softmax output match the Kotlin contract exactly.
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite = converter.convert()
    with open(os.path.join(args.out_dir, "lm.tflite"), "wb") as fh:
        fh.write(tflite)

    print(f"Wrote {args.out_dir}/lm.tflite and vocab.txt — rebuild the app to activate the neural rescorer.")


if __name__ == "__main__":
    main()
