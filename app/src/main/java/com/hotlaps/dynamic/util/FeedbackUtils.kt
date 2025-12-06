package com.hotlaps.dynamic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun Context.sendFeedbackEmail(currentScreen: String? = null) {

    val subject = buildString {
        append("Apex Dynamics Feedback")
        if (!currentScreen.isNullOrEmpty()) {
            append(" – $currentScreen")
        }
    }

    val body = """
        Please describe your feedback:

        ---

        Optional details (edit as you like):
        • What you were doing:
        • Steps to reproduce:
        • Track/Event name:

        ---

        Diagnostics (please leave this section):
        App version: (auto-fill soon)
        Screen: ${currentScreen ?: "Unknown"}
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf("apexdynamics.app@gmail.com"))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    } else {
        Toast.makeText(this, "No email app available", Toast.LENGTH_LONG).show()
    }
}
