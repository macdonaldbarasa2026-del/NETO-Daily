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
    private lateinit var confirmInput: EditText
    private lateinit var primary: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var nameLabel: TextView
    private lateinit var confirmLabel: TextView
    private lateinit var status: TextView

    private var creating = true

    private val bg = Color.rgb(246, 251, 244)
    private val textColor = Color.rgb(18, 33, 30)
    private val muted = Color.rgb(100, 115, 111)
    private val accent = Color.rgb(8, 127, 104)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Supabase.client.auth.currentUserOrNull() != null) {
            openMain()
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

        content.addView(
            brand,
            lp(bottom = 28)
        )

        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.WHITE)
            radius = dp(24).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(225, 232, 228)
            cardElevation = 0f
            setContentPadding(
                dp(24),
                dp(26),
                dp(24),
                dp(24)
            )
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        title = TextView(this).apply {
            textSize = 25f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        subtitle = TextView(this).apply {
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

        confirmInput = input("Enter your password again").apply {
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        primary = MaterialButton(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            cornerRadius = dp(16)
            isAllCaps = false
            textSize = 15f
        }

        switchButton = MaterialButton(this).apply {
            setTextColor(textColor)
            setBackgroundColor(Color.TRANSPARENT)
            cornerRadius = dp(16)
            isAllCaps = false
            textSize = 14f
        }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }

        form.addView(title, lp())
        form.addView(subtitle, lp())
        form.addView(nameLabel, lp())
        form.addView(nameInput, lp(bottom = 14))
        form.addView(label("Email address"), lp())
        form.addView(emailInput, lp(bottom = 14))
        form.addView(label("Password"), lp())
        form.addView(passwordInput, lp(bottom = 14))
        form.addView(confirmLabel, lp())
        form.addView(confirmInput, lp(bottom = 18))
        form.addView(primary, lp(height = 54))
        form.addView(switchButton, lp(height = 48))

        val guestButton = MaterialButton(this).apply {
            text = "Continue as Guest (Instant Access) →"
            setTextColor(Color.rgb(8, 127, 104))
            setBackgroundColor(Color.rgb(234, 245, 239))
            cornerRadius = dp(16)
            isAllCaps = false
            textSize = 14f
            setOnClickListener {
                val store = com.netodaily.app.data.NetoLocalStore(this@AuthActivity)
                val enteredName = nameInput.text?.toString()?.trim().orEmpty()
                if (enteredName.isNotEmpty()) {
                    store.setUserName(enteredName)
                }
                store.saveBoolean("has_entered", true)
                store.setGuestMode(true)
                openMain()
            }
        }
        form.addView(guestButton, lp(height = 48, bottom = 8))

        form.addView(status, lp())

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

        primary.setOnClickListener {
            if (creating) createAccount()
            else signIn()
        }

        switchButton.setOnClickListener {
            if (!primary.isEnabled) return@setOnClickListener
            creating = !creating
            updateMode()
        }

        updateMode()
    }

    private fun updateMode() {

        if (creating) {
            title.text = "Create your account"
            subtitle.text =
                "Set up NETO once, then your conversations stay with you."
            primary.text = "Create account"
            switchButton.text =
                "Already have an account? Sign in"

            nameLabel.visibility = View.VISIBLE
            nameInput.visibility = View.VISIBLE
            confirmLabel.visibility = View.VISIBLE
            confirmInput.visibility = View.VISIBLE
        } else {
            title.text = "Welcome back"
            subtitle.text =
                "Sign in to continue with NETO."
            primary.text = "Sign in"
            switchButton.text =
                "New to NETO? Create an account"

            nameLabel.visibility = View.GONE
            nameInput.visibility = View.GONE
            confirmLabel.visibility = View.GONE
            confirmInput.visibility = View.GONE
        }

        status.visibility = View.GONE
    }

    private fun createAccount() {

        val name = nameInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val confirm = confirmInput.text.toString()

        if (name.length < 2) {
            showError("Please enter your name.")
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS
                .matcher(email)
                .matches()
        ) {
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

                Supabase.client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password

                    data = buildJsonObject {
                        put("display_name", name)
                    }
                }

                if (Supabase.client.auth.currentUserOrNull() != null) {
                    openMain()
                } else {
                    creating = false
                    updateMode()
                    emailInput.setText(email)

                    showMessage(
                        "Account created. Confirm your email, then sign in."
                    )
                }

            } catch (e: Exception) {
                showError(
                    e.message ?: "Account creation failed."
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun signIn() {

        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (!android.util.Patterns.EMAIL_ADDRESS
                .matcher(email)
                .matches()
        ) {
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

                val session =
                    Supabase.client.auth.currentSessionOrNull()

                if (session == null) {
                    showError(
                        "NETO signed in unsuccessfully. No active session was created."
                    )
                    return@launch
                }

                openMain()

            } catch (e: Exception) {
                showError(
                    e.message ?: "Authentication failed."
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun openMain() {

        startActivity(
            android.content.Intent(
                this,
                MainActivity::class.java
            ).apply {
                flags =
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )

        finish()
    }

    private fun showError(message: String) {

        runOnUiThread {
            status.text = message
            status.setTextColor(Color.rgb(150, 55, 45))
            status.visibility = View.VISIBLE
        }
    }

    private fun showMessage(message: String) {

        runOnUiThread {
            status.text = message
            status.setTextColor(accent)
            status.visibility = View.VISIBLE
        }
    }

    private fun setBusy(busy: Boolean) {

        runOnUiThread {
            primary.isEnabled = !busy
            switchButton.isEnabled = !busy

            primary.text =
                if (busy) {
                    if (creating) {
                        "Creating account..."
                    } else {
                        "Signing in..."
                    }
                } else {
                    if (creating) {
                        "Create account"
                    } else {
                        "Sign in"
                    }
                }
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
            minHeight = dp(54)
            setPadding(dp(16), 0, dp(16), 0)
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
