package com.neo.test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Native progress fixture only: changes values, never requests focus or announces them. */
class ProgressActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progress: ProgressBar
    private lateinit var value: TextView
    private val advance = object : Runnable {
        override fun run() {
            progress.progress = (progress.progress + 20).coerceAtMost(progress.max)
            updateValue()
            if (progress.progress < progress.max) handler.postDelayed(this, 3000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)
        progress = findViewById(R.id.download_progress)
        value = findViewById(R.id.download_progress_value)
        updateValue()
        findViewById<Button>(R.id.start_progress).setOnClickListener {
            handler.removeCallbacks(advance)
            if (progress.progress < progress.max) handler.postDelayed(advance, 3000)
        }
        findViewById<Button>(R.id.reset_progress).setOnClickListener {
            handler.removeCallbacks(advance)
            progress.progress = 80
            updateValue()
        }
    }
    private fun updateValue() { value.text = "Value: ${progress.progress} / ${progress.max}" }
    override fun onStop() {
        handler.removeCallbacks(advance)
        super.onStop()
    }
}
