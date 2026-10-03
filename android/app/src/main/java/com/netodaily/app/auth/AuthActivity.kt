package com.netodaily.app.auth

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.netodaily.app.MainActivity
import com.netodaily.app.Supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthActivity : AppCompatActivity() {

    private val scope = MainScope()

    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var primaryButton: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var nameLabel: TextView
    private lateinit var confirmLabel: TextView
    private lateinit var statusText: TextView

    private var creatingAccount = true

    private val bg = Color.rgb(246, 251, 244)
    private val surface = Color.WHITE
    private val textColor = Color.rgb(18, 33, 30)
    private val muted = Color.rgb(100, 115, 111)
    private val accent = Color.rgb(8, 127, 104)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Supabase.client.auth.currentUserOrNull() != null) {
            openApp()
            return
        }

        buildUi()
    }

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
        }

        val brand = TextView(this).apply {
            text = "NETO"
            textSize = 34f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val brandSub = TextView(this).apply {
            text = "DAILY ASSISTANT"
            textSize = 11f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        content.addView(brand, lp())
        content.addView(brandSub, lp(bottom = 28))

        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(surface)
            radius = dp(24).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(225, 232, 228)
            cardElevation = 0f
            setContentPadding(dp(24), dp(26), dp(24), dp(24))
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleText = TextView(this).apply {
            textSize = 25f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        subtitleText = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(24))
        }

        nameLabel = label("Your name")
        nameInput = input("What should NETO call you?")

        emailInput = input("Email address").apply {
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        passwordInput = input("Password").apply {
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        confirmLabel = label("Confirm password")
        confirmPasswordInput = input("Enter your password again").apply {
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }

        primaryButton = MaterialButton(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            cornerRadius = dp(16)
            textSize = 15f
            isAllCaps = false
        }

        switchButton = MaterialButton(this).apply {
            setTextColor(textColor)
            setBackgroundColor(Color.TRANSPARENT)
            cornerRadius = dp(16)
            textSize = 14f
            isAllCaps = false
            strokeWidth = 0
        }

        form.addView(titleText, lp())
        form.addView(subtitleText, lp())
        form.addView(nameLabel, lp())
        form.addView(nameInput, lp(bottom = 14))
        form.addView(label("Email address"), lp())
        form.addView(emailInput, lp(bottom = 14))
        form.addView(label("Password"), lp())
        form.addView(passwordInput, lp(bottom = 14))
        form.addView(confirmLabel, lp())
        form.addView(confirmPasswordInput, lp(bottom = 18))
        form.addView(primaryButton, lp(height = 54))
        form.addView(switchButton, lp(height = 48))
        form.addView(statusText, lp())

        card.addView(
            form,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val footer = TextView(this).apply {
            text =
                "Your conversations and saved information stay connected to your NETO account."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(22), dp(20), 0)
        }

        content.addView(footer, lp())

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        primaryButton.setOnClickListener {
            if (creatingAccount) {
                createAccount()
            } else {
                signIn()
            }
        }

        switchButton.setOnClickListener {
            if (!primaryButton.isEnabled) return@setOnClickListener
            creatingAccount = !creatingAccount
            updateMode()
        }

        updateMode()
    }

    private fun updateMode() {
        if (creatingAccount) {
            titleText.text = "Create your account"
            subtitleText.text =
                "Set up NETO once, then your conversations, memories and saved items stay with you."
            primaryButton.text = "Create account"
            switchButton.text = "Already have an account? Sign in"

            nameLabel.visibility = View.VISIBLE
            nameInput.visibility = View.VISIBLE
            confirmLabel.visibility = View.VISIBLE
            confirmPasswordInput.visibility = View.VISIBLE
        } else {
            titleText.text = "Welcome back"
            subtitleText.text =
                "Sign in to continue your conversations with NETO."
            primaryButton.text = "Sign in"
            switchButton.text = "New to NETO? Create an account"

            nameLabel.visibility = View.GONE
            nameInput.visibility = View.GONE
            confirmLabel.visibility = View.GONE
            confirmPasswordInput.visibility = View.GONE
        }

        statusText.visibility = View.GONE
    }

    private fun createAccount() {
        val name = nameInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val confirm = confirmPasswordInput.text.toString()

        if (name.length < 2) {
            showError("Please enter your name.")
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Please enter a valid email address.")
            return
        }

        if (password.length < 6) {
            showError("Your password must contain at least 6 characters.")
            return
        }

        if (password != confirm) {
            showError("The passwords do not match.")
            return
        }

        setBusy(true)

        scope.launch {
            try {
                val result = Supabase.client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password

                    data = buildJsonObject {
                        put("display_name", name)
                    }
                }

                val user = Supabase.client.auth.currentUserOrNull()

                if (user != null) {
                    openApp()
                } else {
                    showMessage(
                        "Account created. Please confirm your email, then sign in."
                    )
                    creatingAccount = false
                    updateMode()
                    emailInput.setText(email)
                    passwordInput.setText("")
                    confirmPasswordInput.setText("")
                }

            } catch (e: Exception) {
                showError(authError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun signIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Please enter a valid email address.")
            return
        }

        if (password.isEmpty()) {
            showError("Please enter your password.")
            return
        }

        setBusy(true)

        scope.launch {
            try {
                Supabase.client.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }

                val user = Supabase.client.auth.currentUserOrNull()
                val session = Supabase.client.auth.currentSessionOrNull()

                if (user == null || session == null) {
                    showError(
                        "NETO signed in unsuccessfully. No active session was created."
                    )
                    return@launch
                }

                showMessage("Signed in. Opening NETO...")

                openApp()

            } catch (e: Exception) {
                showError(authError(e))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun authError(error: Exception): String {
        val message = error.message?.lowercase().orEmpty()

        return when {
            "invalid login credentials" in message ->
                "The email or password is incorrect."

            "email not confirmed" in message ->
                "Please confirm your email address before signing in."

            "user already registered" in message ->
                "An account with this email already exists. Try signing in."

            "network" in message ||
            "timeout" in message ||
            "unable to resolve" in message ->
                "We couldn't connect to NETO. Check your internet connection."

            else ->
                "Authentication failed: ${error.message ?: "unknown error"}"
        }
    }

    private fun openApp() {
        val session = Supabase.client.auth.currentSessionOrNull()

        if (session == null) {
            showError("NETO has no active session. Please sign in again.")
            return
        }

        try {
            startActivity(
                android.content.Intent(
                    this,
                    MainActivity::class.java
                ).apply {
                    flags =
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        } catch (e: Exception) {
            showError(
                "NETO could not open the main screen: ${e.message ?: "unknown error"}"
            )
        }
    }

    private fun showError(message: String) {
        statusText.text = message
        statusText.setTextColor(Color.rgb(150, 55, 45))
        statusText.visibility = View.VISIBLE
    }

    private fun showMessage(message: String) {
        statusText.text = message
        statusText.setTextColor(accent)
        statusText.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        primaryButton.isEnabled = !busy
        switchButton.isEnabled = !busy

        primaryButton.text =
            if (busy) {
                if (creatingAccount) "Creating account..."
                else "Signing in..."
            } else {
                if (creatingAccount) "Create account"
                else "Sign in"
            }
    }

    private fun label(value: String): TextView =
        TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun input(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(muted)
            background = null
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(54)
            setBackgroundColor(Color.rgb(247, 249, 248))
        }

    private fun lp(
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        bottom: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                height
            } else {
                dp(height)
            }
        ).apply {
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
