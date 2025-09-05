package com.example.minimalapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = "Hello World!"
        textView.textSize = 24f
        textView.textAlignment = TextView.TEXT_ALIGNMENT_CENTER

        setContentView(textView)
    }
}
