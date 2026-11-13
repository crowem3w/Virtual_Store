package org.example.test.recon

import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A relative inverse-depth image (bigger value = nearer), [w] x [h]. */
class DisparityMap(val w: Int, val h: Int, val d: FloatArray) {
    /** Bilinear sample at normalised image coordinates u,v in 0..1. */
    fun sample(u: Float, v: Float): Float {
        val x = (u * (w - 1)).coerceIn(0f, (w - 1).toFloat())
        val y = (v * (h - 1)).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt().coerceAtMost(w - 2); val y0 = y.toInt().coerceAtMost(h - 2)
        val fx = x - x0; val fy = y - y0
        val a = d[y0 * w + x0]; val b = d[y0 * w + x0 + 1]
        val c = d[(y0 + 1) * w + x0]; val e = d[(y0 + 1) * w + x0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + e * fx) * fy
    }
}

/**
 * Monocular depth with a TFLite model bundled at assets/[MODEL_ASSET] (MiDaS-small or Depth-Anything-small
 * style: float32 image in, relative inverse depth out). The input layout (NHWC or NCHW) and size are read
 * from the model. If your model expects different normalisation, change [MEAN] / [STD].
 */
class DepthEstimator(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val inW: Int
    private val inH: Int
    private val nchw: Boolean
    private val outW: Int
    private val outH: Int
    private val input: ByteBuffer
    private val output: ByteBuffer

    init {
        val bytes = try {
            context.assets.open(MODEL_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Depth model missing: add a TFLite depth model as app/src/main/assets/$MODEL_ASSET (see docs/PIPELINE.md).", e,
            )
        }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))

        val inShape = interpreter.getInputTensor(0).shape()
        require(inShape.size == 4) { "Unsupported depth model input shape ${inShape.toList()}" }
        nchw = inShape[1] == 3 && inShape[3] != 3
        inH = if (nchw) inShape[2] else inShape[1]
        inW = if (nchw) inShape[3] else inShape[2]

        val dims = interpreter.getOutputTensor(0).shape().filter { it > 1 }
        require(dims.size == 2) { "Unsupported depth model output shape ${interpreter.getOutputTensor(0).shape().toList()}" }
        outH = dims[0]; outW = dims[1]

        input = ByteBuffer.allocateDirect(inW * inH * 3 * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
    }

    /** [bgr] is an 8-bit 3-channel image as loaded by OpenCV. */
    fun estimate(bgr: Mat): DisparityMap {
        val resized = Mat(); val rgb = Mat(); val f = Mat()
        Imgproc.resize(bgr, resized, Size(inW.toDouble(), inH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        rgb.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0)
        val px = FloatArray(inW * inH * 3)
        f.get(0, 0, px)
        resized.release(); rgb.release(); f.release()

        input.rewind()
        val fb = input.asFloatBuffer()
        if (!nchw) {
            for (i in px.indices) fb.put(i, (px[i] - MEAN[i % 3]) / STD[i % 3])
        } else {
            val plane = inW * inH
            for (c in 0..2) for (i in 0 until plane) fb.put(c * plane + i, (px[i * 3 + c] - MEAN[c]) / STD[c])
        }
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val d = FloatArray(outW * outH)
        output.asFloatBuffer().get(d)
        return DisparityMap(outW, outH, d)
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "models/depth.tflite"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
