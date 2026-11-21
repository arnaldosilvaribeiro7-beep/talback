package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardRedirectToMain(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardRedirectToMain(intent)
    }

    private fun forwardRedirectToMain(sourceIntent: Intent?) {
        val redirectUri = sourceIntent?.data
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OAUTH_REDIRECT
            data = redirectUri
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(mainIntent)
        finish()
    }
}
