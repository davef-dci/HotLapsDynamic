package com.hotlaps.dynamic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.hotlaps.dynamic.BuildConfig

fun Context.sendFeedbackEmail(currentScreen: String? = null) {

    val subject = buildString {
        append("Apex Dynamics Feedback")
        if (!currentScreen.isNullOrEmpty()) {
            append(" – $currentScreen")
        }
    }

    val appVersion = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"

    val body = """
        Please describe your feedback:

        ---

        Optional details (edit as you like):
        • What you were doing:
        • Steps to reproduce:
        • Track/Event name:

        ---

        Diagnostics (please leave this section):
        App version: $appVersion
        Screen: ${currentScreen ?: "Unknown"}
    """.trimIndent()

    // Preferred: apps that explicitly handle mailto: links
    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:") // generic mailto so user can pick account
        putExtra(Intent.EXTRA_EMAIL, arrayOf("apexdynamics.app@gmail.com"))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

    val pm = packageManager

    val canHandleSendTo = emailIntent.resolveActivity(pm) != null

    if (canHandleSendTo) {
        startActivity(emailIntent)
        return
    }

    // Fallback: broader email SEND intent
    val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822" // try to limit to email apps
        putExtra(Intent.EXTRA_EMAIL, arrayOf("apexdynamics.app@gmail.com"))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

    val canHandleSend = fallbackIntent.resolveActivity(pm) != null

    if (canHandleSend) {
        startActivity(Intent.createChooser(fallbackIntent, "Send feedback via…"))
    } else {
        Toast.makeText(this, "No email app available", Toast.LENGTH_LONG).show()
    }
}
