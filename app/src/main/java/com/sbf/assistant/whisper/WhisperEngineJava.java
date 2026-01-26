package com.sbf.assistant.whisper;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class WhisperEngineJava implements WhisperEngine {
    private final String TAG = "WhisperEngineJava";
    private final WhisperUtil mWhisperUtil = new WhisperUtil();

    private final Context mContext;
    private boolean mIsInitialized = false;
    private Interpreter mInterpreter = null;
    private Delegate gpuDelegate;
    private boolean usingGpu = false;

    public WhisperEngineJava(Context context) {
        mContext = context;
    }

    public boolean isUsingGpu() {
        return usingGpu;
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public boolean initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        // Load model
        loadModel(modelPath);
        Log.d(TAG, "Model is loaded..." + modelPath);

        // Load filters and vocab
        boolean ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath);
        if (ret) {
            mIsInitialized = true;
            Log.d(TAG, "Filters and Vocab are loaded..." + vocabPath);
        } else {
            mIsInitialized = false;
            Log.d(TAG, "Failed to load Filters and Vocab...");
        }

        return mIsInitialized;
    }

    // Unload the model by closing the interpreter
    @Override
    public void deinitialize() {
        if (mInterpreter != null) {
            mInterpreter.close();
            mInterpreter = null; // Optional: Set to null to avoid accidental reuse
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
    }

    @Override
    public String transcribeFile(String wavePath) {
        // Calculate Mel spectrogram
        Log.d(TAG, "Calculating Mel spectrogram...");
        float[] melSpectrogram = getMelSpectrogram(wavePath);
        Log.d(TAG, "Mel spectrogram is calculated...!");

        // Perform inference
        String result = runInference(melSpectrogram);
        Log.d(TAG, "Inference is executed...!");

        return result;
    }

    @Override
    public String transcribeBuffer(float[] samples) {
        if (!mIsInitialized || mInterpreter == null) {
            Log.e(TAG, "Engine not initialized");
            return null;
        }

        // Pad or truncate samples to fixed input size
        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);

        // Calculate mel spectrogram
        int cores = Runtime.getRuntime().availableProcessors();
        float[] melSpectrogram = mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, cores);

        // Run inference
        return runInference(melSpectrogram);
    }

    // Load TFLite model with GPU acceleration when available
    private void loadModel(String modelPath) throws IOException {
        ByteBuffer tfliteModel;
        try (FileInputStream fileInputStream = new FileInputStream(modelPath);
             FileChannel fileChannel = fileInputStream.getChannel()) {
            long startOffset = 0;
            long declaredLength = fileChannel.size();
            tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }

        int numThreads = Runtime.getRuntime().availableProcessors();
        usingGpu = false;

        boolean gpuDelegateAvailable = false;
        try {
            Class.forName("org.tensorflow.lite.gpu.GpuDelegateFactory$Options");
            gpuDelegateAvailable = true;
        } catch (Throwable t) {
            Log.w(TAG, "GPU delegate classes not available, using CPU.");
        }

        if (gpuDelegateAvailable) {
            try {
                Log.d(TAG, "Attempting TFLite GPU delegate...");
                GpuDelegate gpuDelegateInstance = new GpuDelegate();
                gpuDelegate = gpuDelegateInstance;

                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(numThreads);
                options.addDelegate(gpuDelegateInstance);

                mInterpreter = new Interpreter(tfliteModel, options);
                usingGpu = true;
                Log.d(TAG, "TFLite GPU delegate enabled for Whisper");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "GPU delegate failed: " + t.getMessage());
                closeDelegate();
            }
        }

        // Fallback: CPU only with XNNPACK
        Log.d(TAG, "Falling back to CPU with XNNPACK...");
        Interpreter.Options cpuOptions = new Interpreter.Options();
        cpuOptions.setNumThreads(numThreads);
        // XNNPACK is enabled by default in recent TFLite versions

        mInterpreter = new Interpreter(tfliteModel, cpuOptions);
        Log.d(TAG, "CPU inference enabled for Whisper (threads=" + numThreads + ")");
    }

    private void closeDelegate() {
        if (gpuDelegate != null) {
            try {
                gpuDelegate.close();
            } catch (Exception ignored) {}
            gpuDelegate = null;
        }
    }

    private float[] getMelSpectrogram(String wavePath) {
        // Get samples in PCM_FLOAT format
        float[] samples = WaveUtil.getSamples(wavePath);

        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);

        int cores = Runtime.getRuntime().availableProcessors();
        return mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, cores);
    }

    private String runInference(float[] inputData) {
        // Create input tensor
        Tensor inputTensor = mInterpreter.getInputTensor(0);
        TensorBuffer inputBuffer = TensorBuffer.createFixedSize(inputTensor.shape(), inputTensor.dataType());
//        printTensorDump("Input Tensor Dump ===>", inputTensor);

        // Create output tensor
        Tensor outputTensor = mInterpreter.getOutputTensor(0);
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32);
//        printTensorDump("Output Tensor Dump ===>", outputTensor);

        // Load input data
        int inputSize = inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * Float.BYTES;
        ByteBuffer inputBuf = ByteBuffer.allocateDirect(inputSize);
        inputBuf.order(ByteOrder.nativeOrder());
        for (float input : inputData) {
            inputBuf.putFloat(input);
        }

        // To test mel data as a input directly
//        try {
//            byte[] bytes = Files.readAllBytes(Paths.get("/data/user/0/com.example.tfliteaudio/files/mel_spectrogram.bin"));
//            inputBuf = ByteBuffer.wrap(bytes);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }

        inputBuffer.loadBuffer(inputBuf);

//        Log.d(TAG, "Before inference...");
        // Run inference
        mInterpreter.run(inputBuffer.getBuffer(), outputBuffer.getBuffer());
//        Log.d(TAG, "After inference...");

        // Retrieve the results
        int outputLen = outputBuffer.getIntArray().length;
        Log.d(TAG, "output_len: " + outputLen);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < outputLen; i++) {
            int token = outputBuffer.getBuffer().getInt();
            if (token == mWhisperUtil.getTokenEOT())
                break;

            // Get word for token and Skip additional token
            if (token < mWhisperUtil.getTokenEOT()) {
                String word = mWhisperUtil.getWordFromToken(token);
                //Log.d(TAG, "Adding token: " + token + ", word: " + word);
                result.append(word);
            } else {
                if (token == mWhisperUtil.getTokenTranscribe())
                    Log.d(TAG, "It is Transcription...");

                if (token == mWhisperUtil.getTokenTranslate())
                    Log.d(TAG, "It is Translation...");

                String word = mWhisperUtil.getWordFromToken(token);
                Log.d(TAG, "Skipping token: " + token + ", word: " + word);
            }
        }

        return result.toString();
    }

    private void printTensorDump(String message, Tensor tensor) {
        Log.d(TAG,"Output Tensor Dump ===>");
        Log.d(TAG, "  shape.length: " + tensor.shape().length);
        for (int i = 0; i < tensor.shape().length; i++)
            Log.d(TAG, "    shape[" + i + "]: " + tensor.shape()[i]);
        Log.d(TAG, "  dataType: " + tensor.dataType());
        Log.d(TAG, "  name: " + tensor.name());
        Log.d(TAG, "  numBytes: " + tensor.numBytes());
        Log.d(TAG, "  index: " + tensor.index());
        Log.d(TAG, "  numDimensions: " + tensor.numDimensions());
        Log.d(TAG, "  numElements: " + tensor.numElements());
        Log.d(TAG, "  shapeSignature.length: " + tensor.shapeSignature().length);
        Log.d(TAG, "  quantizationParams.getScale: " + tensor.quantizationParams().getScale());
        Log.d(TAG, "  quantizationParams.getZeroPoint: " + tensor.quantizationParams().getZeroPoint());
        Log.d(TAG, "==================================================================");
    }
}
