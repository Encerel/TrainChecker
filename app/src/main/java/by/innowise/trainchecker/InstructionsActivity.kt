package by.innowise.trainchecker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import by.innowise.trainchecker.databinding.ActivityInstructionsBinding

class InstructionsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInstructionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstructionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Инструкция"

        binding.instructionImage.setOnClickListener {
            val intent = Intent(this, ImageViewerActivity::class.java).apply {
                putExtra("image_res_id", R.drawable.instruction_step3)
            }
            startActivity(intent)
        }

        binding.instructionImage2.setOnClickListener {
            val intent = Intent(this, ImageViewerActivity::class.java).apply {
                putExtra("image_res_id", R.drawable.instruction_step4)
            }
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
