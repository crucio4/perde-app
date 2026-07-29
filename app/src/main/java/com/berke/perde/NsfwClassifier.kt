package com.berke.perde

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
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

    /** Son yukleme hatasi. Tani ekraninda gosteriliyor. */
    var lastError: String? = null
        private set

    init {
        // GPU delegate inference maliyetini ciddi dusuruyor ama dynamic-range
        // quantize edilmis modellerde sik sik kurulamiyor. Basarisiz olursa
        // CPU ile devam etmek DOGRU davranis — eskiden burada pes ediliyordu
        // ve siniflandirici komple olu kaliyordu.
        if (!tryInit(context, useGpu = true)) {
            Log.w(TAG, "GPU ile kurulamadi, CPU ile deneniyor")
            tryInit(context, useGpu = false)
        }
    }

    private fun tryInit(context: Context, useGpu: Boolean): Boolean = try {
        val options = Interpreter.Options().apply {
            setNumThreads(2)
            if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            }
        }
        interpreter = Interpreter(loadModel(context), options)
        lastError = null
        Log.i(TAG, "Model yuklendi (gpu=$useGpu)")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Model yuklenemedi (gpu=$useGpu): ${e.message}", e)
        lastError = "${if (useGpu) "gpu" else "cpu"}: ${e.javaClass.simpleName}: ${e.message}"
        // Yarim kalan kaynaklari birak, yoksa ikinci deneme kirli state gorur
        runCatching { gpuDelegate?.close() }
        gpuDelegate = null
        interpreter = null
        false
    }

    private fun loadModel(context: Context): ByteBuffer {
        // Tercih edilen yol: memory-map. Bunun icin asset SIKISTIRILMAMIS olmali
        // (app/build.gradle.kts -> androidResources { noCompress += "tflite" }).
        try {
            val fd = context.assets.openFd(MODEL_FILE)
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        } catch (e: IOException) {
            // Asset sikistirilmissa openFd calismaz. Bu durumda tamamini
            // bellege okumak tek secenek — ~4.5 MB, kabul edilebilir.
            Log.w(TAG, "openFd basarisiz (asset sikistirilmis olabilir): ${e.message}")
        }

        context.assets.open(MODEL_FILE).use { input ->
            val bytes = input.readBytes()
            return ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply { put(bytes); rewind() }
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
