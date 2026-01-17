package com.example.wordle

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException

class LoginActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var continueButton: Button
    private lateinit var progress: ProgressBar

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var firebaseReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        statusText = findViewById(R.id.login_status)
        signInButton = findViewById(R.id.btn_sign_in)
        continueButton = findViewById(R.id.btn_continue)
        progress = findViewById(R.id.login_progress)

        signInButton.setOnClickListener { signInAnonymously() }
        continueButton.setOnClickListener { goToGame() }

        firebaseReady = FirebaseApp.initializeApp(this) != null
        if (firebaseReady) {
            updateUi()
            setLoading(false)
        } else {
            Toast.makeText(this, R.string.login_unavailable, Toast.LENGTH_LONG).show()
            statusText.text = getString(R.string.login_unavailable)
            signInButton.visibility = View.GONE
            continueButton.visibility = View.VISIBLE
            setLoading(false)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!firebaseReady) {
            return
        }
        updateUi()
        setLoading(false)
    }

    private fun updateUi() {
        if (!firebaseReady) {
            return
        }
        val user = auth.currentUser
        if (user == null) {
            statusText.text = getString(R.string.login_status_signed_out)
            signInButton.visibility = View.VISIBLE
            continueButton.visibility = View.GONE
        } else {
            statusText.text = getString(R.string.login_status_signed_in, user.uid)
            signInButton.visibility = View.GONE
            continueButton.visibility = View.VISIBLE
        }
    }

    private fun setLoading(isLoading: Boolean) {
        progress.visibility = if (isLoading) View.VISIBLE else View.GONE
        signInButton.isEnabled = !isLoading
    }

    private fun signInAnonymously() {
        if (!firebaseReady) {
            Toast.makeText(this, R.string.login_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        auth.signInAnonymously()
            .addOnCompleteListener { task ->
                setLoading(false)
                if (task.isSuccessful) {
                    updateUi()
                    goToGame()
                } else {
                    updateUi()
                    val exception = task.exception
                    val detail = when (exception) {
                        is FirebaseAuthException -> exception.errorCode
                        is FirebaseNetworkException -> "NETWORK"
                        else -> exception?.javaClass?.simpleName ?: "UNKNOWN"
                    }
                    val detailText = exception?.localizedMessage?.let { "$detail: $it" } ?: detail
                    Toast.makeText(
                        this,
                        getString(R.string.login_error_detail, detailText),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun goToGame() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
