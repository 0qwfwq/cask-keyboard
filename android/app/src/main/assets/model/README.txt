Neural language model (optional) for the Cask autocorrect rescorer.

Drop two files in THIS folder to activate the on-device neural rescorer:

  lm.tflite   next-word LM. Input  int32  [1, 8]   (last 8 token ids, left-padded with 0)
                            Output float32 [1, V]   (softmax over the V-word vocabulary)
  vocab.txt   one token per line; line index == token id.
              line 0 = <pad>, line 1 = <unk>

Generate both with:  python tools/train_lm.py --corpus <your_text.txt>

Until these exist the keyboard runs on its statistical engine alone (noisy-channel + adaptive
n-gram), which is fully functional on its own — nothing here is required for the keyboard to work.
The constants (SEQ_LEN=8, pad id 0, unk id 1) must match NeuralRescorer.kt and train_lm.py.
