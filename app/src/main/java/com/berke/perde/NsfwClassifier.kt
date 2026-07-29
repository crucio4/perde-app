package com.berke.perde

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device NSFW sınıflandırıcı. Hiçbir veri cihazdan çıkmaz.
 *
 * Beklenen model: assets/nsfw.tflite
 *   giriş : [1, 224, 224, 3] float32, 0..1 normalize
 *   çıkış : [1, 5] softmax -> [drawings, hentai, neutral, porn, sexy]
 *
 * Farklı bir model kullanırsan INPUT_SIZE ve çıkış sınıf sırasını
 * Config.kt içinde güncelle.
 */
class NsfwClassifier(context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * Config.INPUT_SIZE * Config.INPUT_SIZE * 3)
        .order(ByteOrder.nativeOrder())

    private val pixels = IntArray(Config.INPUT_SIZE * Config.INPUT_SIZE)
    private val output = Array(1) { FloatArray(NUM_CLASSES) }

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                // GPU varsa kullan — inference maliyeti ciddi düşer, batarya için önemli
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                    Log.i(TAG, "GPU delegate aktif")
                }
            }
            interpreter = Interpreter(loadModel(context), options)
            Log.i(TAG, "Model yüklendi")
        } catch (e: Exception) {
            Log.e(TAG, "Model yüklenemedi: ${e.message}", e)
        }
    }

    private fun loadModel(context: Context): ByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }
    }

    /** Model gercekten yuklendi mi? Tani ekrani icin. */
    fun isReady(): Boolean = interpreter != null

    /**
     * @return [drawings, hentai, neutral, porn, sexy] olasılıkları,
     *         model yoksa null
     */
    fun classify(bitmap: Bitmap): FloatArray? {
        val itp = interpreter ?: return null

        val scaled = if (bitmap.width != Config.INPUT_SIZE || bitmap.height != Config.INPUT_SIZE) {
            Bitmap.createScaledBitmap(bitmap, Config.INPUT_SIZE, Config.INPUT_SIZE, true)
        } else bitmap

        inputBuffer.rewind()
        scaled.getPixels(pixels, 0, Config.INPUT_SIZE, 0, 0, Config.INPUT_SIZE, Config.INPUT_SIZE)

        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
            inputBuffer.putFloat((p and 0xFF) / 255f)          // B
        }

        if (scaled !== bitmap) scaled.recycle()

        return try {
            itp.run(inputBuffer, output)
            output[0].copyOf()
        } catch (e: Exception) {
            Log.e(TAG, "Inference hatası: ${e.message}")
            null
        }
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
    }

    companion object {
        private const val TAG = "NsfwClassifier"
        private const val MODEL_FILE = "nsfw.tflite"
        private const val NUM_CLASSES = 5
    }
}
