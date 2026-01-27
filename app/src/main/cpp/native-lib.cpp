/**
 * Native library placeholder for potential future use.
 *
 * Possible future uses:
 * - Custom audio processing (resampling, VAD)
 * - Optimized tensor operations
 * - Direct LiteRT/TFLite integration
 *
 * Currently this file is a stub to maintain the build configuration.
 * The app functions fully without native code at this time.
 */

#include <jni.h>

// Placeholder function - not currently used by the app
extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}