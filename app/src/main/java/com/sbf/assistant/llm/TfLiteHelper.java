package com.sbf.assistant.llm;

import android.util.Log;

import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Helper class for TensorFlow Lite interpreter initialization.
 * Provides common functionality for loading models with optional GPU acceleration.
 */
public class TfLiteHelper {
    private static final String TAG = "TfLiteHelper";

    /**
     * Result of creating an interpreter.
     */
    public static class InterpreterResult {
        public final Interpreter interpreter;
        public final Delegate gpuDelegate;
        public final boolean usingGpu;

        public InterpreterResult(Interpreter interpreter, Delegate gpuDelegate, boolean usingGpu) {
            this.interpreter = interpreter;
            this.gpuDelegate = gpuDelegate;
            this.usingGpu = usingGpu;
        }

        /**
         * Release resources. Call this when done with the interpreter.
         */
        public void release() {
            if (interpreter != null) {
                interpreter.close();
            }
            if (gpuDelegate != null) {
                try {
                    gpuDelegate.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Load a TFLite model from file path with optional GPU acceleration.
     *
     * @param modelPath Path to the .tflite model file
     * @param preferGpu Whether to attempt GPU acceleration
     * @return InterpreterResult containing the interpreter and GPU delegate (if used)
     * @throws IOException If the model file cannot be read
     */
    public static InterpreterResult createInterpreter(String modelPath, boolean preferGpu) throws IOException {
        ByteBuffer tfliteModel = loadModelFile(modelPath);
        int numThreads = Runtime.getRuntime().availableProcessors();

        if (preferGpu && isGpuDelegateAvailable()) {
            try {
                Log.d(TAG, "Attempting TFLite GPU delegate...");
                GpuDelegate gpuDelegate = new GpuDelegate();

                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(numThreads);
                options.addDelegate(gpuDelegate);

                Interpreter interpreter = new Interpreter(tfliteModel, options);
                Log.d(TAG, "TFLite GPU delegate enabled");
                return new InterpreterResult(interpreter, gpuDelegate, true);
            } catch (Throwable t) {
                Log.w(TAG, "GPU delegate failed, falling back to CPU: " + t.getMessage());
            }
        }

        // Fallback to CPU
        Log.d(TAG, "Using CPU inference (threads=" + numThreads + ")");
        Interpreter.Options cpuOptions = new Interpreter.Options();
        cpuOptions.setNumThreads(numThreads);

        Interpreter interpreter = new Interpreter(tfliteModel, cpuOptions);
        return new InterpreterResult(interpreter, null, false);
    }

    /**
     * Load a TFLite model file into a ByteBuffer.
     */
    public static ByteBuffer loadModelFile(String modelPath) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(modelPath);
             FileChannel fileChannel = fileInputStream.getChannel()) {
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
        }
    }

    /**
     * Check if GPU delegate classes are available on this device.
     */
    public static boolean isGpuDelegateAvailable() {
        try {
            Class.forName("org.tensorflow.lite.gpu.GpuDelegateFactory$Options");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "GPU delegate classes not available");
            return false;
        }
    }
}
