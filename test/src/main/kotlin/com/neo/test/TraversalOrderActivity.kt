package com.neo.test

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Isolated View fixtures for issue #96; deliberately contains no scroll/focus group. */
class TraversalOrderActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traversal_order)
    }
}
