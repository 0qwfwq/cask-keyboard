package com.example.cask.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ln

/**
 * Neural second-pass rescorer backed by an on-device TensorFlow Lite language model.
 *
 * This is **complete, real wiring** — it just needs a model to point at. Drop two files into
 * `assets/model/`:
 *
 *  - `lm.tflite` — a next-word LM that takes an int32 tensor of shape `[1, SEQ_LEN]` (the last
 *    [SEQ_LEN] context token ids, left-padded with 0) and returns a float tensor of shape
 *    `[1, vocabSize]` of probabilities (softmax) over the vocabulary.
 *  - `vocab.txt` — the vocabulary, one token per line; line index = token id. Index 0 is the
 *    padding token and index 1 should be the unknown (`<unk>`) token.
 *
 * Train them with `tools/train_lm.py` (see README). Until both files exist, [isReady] is false and
 * the engine runs on the statistical model alone — nothing here throws.
 */
class NeuralRescorer private constructor(
    private val interpreter: Interpreter,
    private val tokenToId: Map<String, Int>,
    private val vocabSize: Int,
    private val seqLen: Int,
) : Rescorer {

    override val isReady: Boolean = true

    private val inputBuf: IntArray = IntArray(seqLen)
    private val output: Array<FloatArray> = arrayOf(FloatArray(vocabSize))

    override fun rescore(context: List<String>, candidates: List<String>): DoubleArray {
        val out = DoubleArray(candidates.size)
        runCatching {
            // Left-pad / right-truncate the context to the model's window.
            inputBuf.fill(PAD_ID)
            val window = if (context.size > seqLen) context.subList(context.size - seqLen, context.size) else context
            val offset = seqLen - window.size
            for (i in window.indices) inputBuf[offset + i] = tokenToId[window[i]] ?: UNK_ID

            val inputTensor = arrayOf(inputBuf)
            interpreter.run(inputTensor, output)
            val probs = output[0]

            for (i in candidates.indices) {
                val id = tokenToId[candidates[i]] ?: UNK_ID
                val p = if (id in probs.indices) probs[id] else 0f
                out[i] = NEURAL_WEIGHT * ln((p + EPS).toDouble())
            }
        }.onFailure { Log.w(TAG, "neural rescore failed; falling back", it) }
        return out
    }

    fun close() = runCatching { interpreter.close() }

    companion object {
        private const val TAG = "CaskNeural"
        private const val MODEL_ASSET = "model/lm.tflite"
        private const val VOCAB_ASSET = "model/vocab.txt"
        private const val PAD_ID = 0
        private const val UNK_ID = 1
        private const val SEQ_LEN = 8
        private const val EPS = 1e-9
        private const val NEURAL_WEIGHT = 1.0 // additive weight of the neural term in log space

        /**
         * Build a rescorer if a model is installed, otherwise return [IdentityRescorer]. Never throws:
         * a missing/incompatible model just leaves the engine on its statistical path.
         */
        fun tryLoad(context: Context): Rescorer {
            return runCatching {
                val vocab = loadVocab(context)
                if (vocab.isEmpty()) return IdentityRescorer
                val model = loadModelFile(context)
                val interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
                NeuralRescorer(interpreter, vocab, vocab.size, SEQ_LEN)
            }.getOrElse {
                Log.i(TAG, "No usable neural model installed; using statistical engine only.")
                IdentityRescorer
            }
        }

        private fun loadVocab(context: Context): Map<String, Int> {
            val map = HashMap<String, Int>()
            context.assets.open(VOCAB_ASSET).bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, line -> map[line.trim()] = idx }
            }
            return map
        }

        private fun loadModelFile(context: Context): MappedByteBuffer {
            context.assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { input ->
                    val channel: FileChannel = input.channel
                    return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        // Kept for callers that want to size their own buffers against the model contract.
        @Suppress("unused")
        fun emptyInput(seqLen: Int): ByteBuffer =
            ByteBuffer.allocateDirect(seqLen * 4).order(ByteOrder.nativeOrder())
    }
}
