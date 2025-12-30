package by.innowise.trainchecker

import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.app.AppCompatActivity
import by.innowise.trainchecker.databinding.ActivityImageViewerBinding
import kotlin.math.max
import kotlin.math.min

class ImageViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageViewerBinding
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private var translationX = 0f
    private var translationY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isScaling = false
    
    companion object {
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 10.0f
        private const val DRAG_SENSITIVITY = 2.0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageResId = intent.getIntExtra("image_res_id", -1)
        if (imageResId != -1) {
            binding.zoomableImageView.setImageResource(imageResId)
        }

        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())

        binding.closeButton.setOnClickListener {
            finish()
        }

        binding.zoomableImageView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isScaling) {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        activePointerId = event.getPointerId(0)
                    }
                }
                
                MotionEvent.ACTION_MOVE -> {
                    if (!isScaling && scaleFactor > 1.0f && event.pointerCount == 1) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex != -1) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            
                            val dx = (x - lastTouchX) * DRAG_SENSITIVITY
                            val dy = (y - lastTouchY) * DRAG_SENSITIVITY
                            
                            translationX += dx
                            translationY += dy
                            
                            // Ограничиваем перемещение в зависимости от масштаба
                            val maxTranslationX = binding.zoomableImageView.width * (scaleFactor - 1) / 2
                            val maxTranslationY = binding.zoomableImageView.height * (scaleFactor - 1) / 2
                            
                            translationX = translationX.coerceIn(-maxTranslationX, maxTranslationX)
                            translationY = translationY.coerceIn(-maxTranslationY, maxTranslationY)
                            
                            binding.zoomableImageView.translationX = translationX
                            binding.zoomableImageView.translationY = translationY
                            
                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    isScaling = false
                }
                
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isScaling = true
                }
                
                MotionEvent.ACTION_POINTER_UP -> {
                    isScaling = false
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId && event.pointerCount > 1) {
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        lastTouchX = event.getX(newPointerIndex)
                        lastTouchY = event.getY(newPointerIndex)
                        activePointerId = event.getPointerId(newPointerIndex)
                    }
                }
            }
            true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }
        
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(MIN_SCALE, MAX_SCALE)
            
            binding.zoomableImageView.scaleX = scaleFactor
            binding.zoomableImageView.scaleY = scaleFactor
            
            // Сбрасываем перемещение при уменьшении до исходного размера
            if (scaleFactor <= MIN_SCALE) {
                translationX = 0f
                translationY = 0f
                binding.zoomableImageView.translationX = 0f
                binding.zoomableImageView.translationY = 0f
            } else {
                // Ограничиваем перемещение при масштабировании
                val maxTranslationX = binding.zoomableImageView.width * (scaleFactor - 1) / 2
                val maxTranslationY = binding.zoomableImageView.height * (scaleFactor - 1) / 2
                
                translationX = translationX.coerceIn(-maxTranslationX, maxTranslationX)
                translationY = translationY.coerceIn(-maxTranslationY, maxTranslationY)
                
                binding.zoomableImageView.translationX = translationX
                binding.zoomableImageView.translationY = translationY
            }
            
            return true
        }
        
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    }
}
