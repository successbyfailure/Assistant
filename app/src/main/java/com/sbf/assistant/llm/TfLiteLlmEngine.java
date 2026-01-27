package com.sbf.assistant.llm;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * TensorFlow Lite engine for running LLM models.
 * Supports autoregressive text generation with GPU acceleration.
 */
public class TfLiteLlmEngine {
    private static final String TAG = "TfLiteLlmEngine";

    private final Context mContext;
    private TfLiteHelper.InterpreterResult interpreterResult;
    private Interpreter mInterpreter;
    private boolean mIsInitialized = false;
    private boolean usingGpu = false;

    // Model configuration
    private int maxSeqLen = 512;
    private int vocabSize = 32000;
    private int hiddenSize = 256;

    // Input/output tensor info
    private int[] inputShape;
    private int[] outputShape;
    private DataType inputType;
    private DataType outputType;

    public TfLiteLlmEngine(Context context) {
        mContext = context;
    }

    public boolean isInitialized() {
        return mIsInitialized;
    }

    public boolean isUsingGpu() {
        return usingGpu;
    }

    public int getMaxSeqLen() {
        return maxSeqLen;
    }

    public int getVocabSize() {
        return vocabSize;
    }

    /**
     * Initialize the LLM engine with model path.
     */
    public boolean initialize(String modelPath) throws IOException {
        try {
            loadModel(modelPath);
            extractModelInfo();
            mIsInitialized = true;
            Log.d(TAG, "LLM Engine initialized. GPU=" + usingGpu +
                    ", maxSeqLen=" + maxSeqLen + ", vocabSize=" + vocabSize);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize LLM engine", e);
            mIsInitialized = false;
            return false;
        }
    }

    /**
     * Deinitialize and release resources.
     */
    public void deinitialize() {
        if (interpreterResult != null) {
            interpreterResult.release();
            interpreterResult = null;
        }
        mInterpreter = null;
        mIsInitialized = false;
    }

    /**
     * Run inference for next token prediction.
     *
     * @param inputIds Array of input token IDs
     * @return Logits array for next token prediction
     */
    public float[] runInference(int[] inputIds) {
        if (!mIsInitialized || mInterpreter == null) {
            Log.e(TAG, "Engine not initialized");
            return null;
        }

        try {
            // Prepare input buffer
            int seqLen = Math.min(inputIds.length, maxSeqLen);
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(seqLen * 4);
            inputBuffer.order(ByteOrder.nativeOrder());

            for (int i = 0; i < seqLen; i++) {
                inputBuffer.putInt(inputIds[i]);
            }
            inputBuffer.rewind();

            // Prepare output buffer (logits for vocab)
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(vocabSize * 4);
            outputBuffer.order(ByteOrder.nativeOrder());

            // Run inference
            mInterpreter.run(inputBuffer, outputBuffer);

            // Extract logits
            outputBuffer.rewind();
            float[] logits = new float[vocabSize];
            for (int i = 0; i < vocabSize; i++) {
                logits[i] = outputBuffer.getFloat();
            }

            return logits;
        } catch (Exception e) {
            Log.e(TAG, "Inference failed", e);
            return null;
        }
    }

    /**
     * Run inference with multiple outputs (for models with KV cache).
     * Note: This is a more advanced API for models that use KV caching.
     */
    public float[] runInferenceWithCache(int[] inputIds, Object[] kvCache) {
        if (!mIsInitialized || mInterpreter == null) {
            Log.e(TAG, "Engine not initialized");
            return null;
        }

        try {
            int seqLen = Math.min(inputIds.length, maxSeqLen);

            // Prepare input buffer
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(seqLen * 4);
            inputBuffer.order(ByteOrder.nativeOrder());
            for (int i = 0; i < seqLen; i++) {
                inputBuffer.putInt(inputIds[i]);
            }
            inputBuffer.rewind();

            // Prepare inputs array (input_ids + optional KV cache)
            Object[] inputs;
            if (kvCache != null && kvCache.length > 0) {
                inputs = new Object[1 + kvCache.length];
                inputs[0] = inputBuffer;
                System.arraycopy(kvCache, 0, inputs, 1, kvCache.length);
            } else {
                inputs = new Object[]{inputBuffer};
            }

            // Prepare outputs (indexed by output tensor number)
            Map<Integer, Object> outputs = new HashMap<>();
            ByteBuffer logitsBuffer = ByteBuffer.allocateDirect(vocabSize * 4);
            logitsBuffer.order(ByteOrder.nativeOrder());
            outputs.put(0, logitsBuffer);

            // Run inference
            mInterpreter.runForMultipleInputsOutputs(inputs, outputs);

            // Extract logits
            logitsBuffer.rewind();
            float[] logits = new float[vocabSize];
            for (int i = 0; i < vocabSize; i++) {
                logits[i] = logitsBuffer.getFloat();
            }

            return logits;
        } catch (Exception e) {
            Log.e(TAG, "Inference with cache failed", e);
            return null;
        }
    }

    /**
     * Sample next token from logits using temperature sampling.
     */
    public int sampleToken(float[] logits, float temperature, int topK) {
        if (logits == null || logits.length == 0) {
            return -1;
        }

        // Apply temperature
        if (temperature > 0) {
            for (int i = 0; i < logits.length; i++) {
                logits[i] /= temperature;
            }
        }

        // Softmax
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float logit : logits) {
            maxLogit = Math.max(maxLogit, logit);
        }

        float sumExp = 0;
        float[] probs = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = (float) Math.exp(logits[i] - maxLogit);
            sumExp += probs[i];
        }

        for (int i = 0; i < probs.length; i++) {
            probs[i] /= sumExp;
        }

        // Top-K sampling
        if (topK > 0 && topK < probs.length) {
            // Find top-K indices
            int[] topIndices = new int[topK];
            float[] topProbs = new float[topK];

            for (int i = 0; i < topK; i++) {
                int maxIdx = 0;
                float maxProb = -1;
                for (int j = 0; j < probs.length; j++) {
                    if (probs[j] > maxProb) {
                        boolean alreadySelected = false;
                        for (int k = 0; k < i; k++) {
                            if (topIndices[k] == j) {
                                alreadySelected = true;
                                break;
                            }
                        }
                        if (!alreadySelected) {
                            maxProb = probs[j];
                            maxIdx = j;
                        }
                    }
                }
                topIndices[i] = maxIdx;
                topProbs[i] = maxProb;
            }

            // Renormalize top-K probs
            float topSum = 0;
            for (float p : topProbs) topSum += p;
            for (int i = 0; i < topK; i++) topProbs[i] /= topSum;

            // Sample from top-K
            float r = (float) Math.random();
            float cumSum = 0;
            for (int i = 0; i < topK; i++) {
                cumSum += topProbs[i];
                if (r <= cumSum) {
                    return topIndices[i];
                }
            }
            return topIndices[topK - 1];
        }

        // Regular sampling
        float r = (float) Math.random();
        float cumSum = 0;
        for (int i = 0; i < probs.length; i++) {
            cumSum += probs[i];
            if (r <= cumSum) {
                return i;
            }
        }

        return probs.length - 1;
    }

    /**
     * Greedy decoding - select token with highest probability.
     */
    public int greedyDecode(float[] logits) {
        if (logits == null || logits.length == 0) {
            return -1;
        }

        int maxIdx = 0;
        float maxVal = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > maxVal) {
                maxVal = logits[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    private void loadModel(String modelPath) throws IOException {
        interpreterResult = TfLiteHelper.createInterpreter(modelPath, true);
        mInterpreter = interpreterResult.interpreter;
        usingGpu = interpreterResult.usingGpu;
        Log.d(TAG, "LLM model loaded. GPU=" + usingGpu);
    }

    private void extractModelInfo() {
        if (mInterpreter == null) return;

        try {
            // Get input tensor info
            Tensor inputTensor = mInterpreter.getInputTensor(0);
            inputShape = inputTensor.shape();
            inputType = inputTensor.dataType();

            if (inputShape.length >= 2) {
                maxSeqLen = inputShape[1];
            }

            // Get output tensor info
            Tensor outputTensor = mInterpreter.getOutputTensor(0);
            outputShape = outputTensor.shape();
            outputType = outputTensor.dataType();

            // Try to infer vocab size from output shape
            if (outputShape.length >= 2) {
                vocabSize = outputShape[outputShape.length - 1];
            }

            Log.d(TAG, "Input shape: " + arrayToString(inputShape) + ", type: " + inputType);
            Log.d(TAG, "Output shape: " + arrayToString(outputShape) + ", type: " + outputType);
        } catch (Exception e) {
            Log.w(TAG, "Could not extract model info", e);
        }
    }

    private String arrayToString(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i]);
            if (i < arr.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}
