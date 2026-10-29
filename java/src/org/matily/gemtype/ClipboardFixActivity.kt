// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.android.inputmethod.latin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.Action
import org.matily.gemtype.core.GeminiException
import org.smc.inputmethod.indic.setup.GemTypeKeyActivity

/**
 * Opened by the "Fix clipboard" Quick Settings tile. Android 10+ lets only the focused app read
 * the clipboard, so this transparent activity waits for window focus, fixes the clipboard text,
 * writes the result back and shows a small card at the top (with Undo) before closing itself.
 */
internal class ClipboardFixActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: TextView
    private var started = false
    private var autoClose: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GemType.isEnabled) {
            finish()
            return
        }
        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        detail = TextView(this).apply {
            setTextColor(0xFFC4CBD6.toInt())
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        progress = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFA8C7FA.toInt())
        }
        action = TextView(this).apply {
            setTextColor(0xFFA8C7FA.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(detail)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(0xFF2A2F38.toInt())
            }
            elevation = 12 * dp
            addView(ImageView(this@ClipboardFixActivity).apply { setImageResource(R.drawable.gemtype_mark) },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(progress, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(action)
        }
        val root = FrameLayout(this)
        // Top, not bottom: Android 13+ shows its own clipboard preview in the bottom corner.
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP,
        ).apply { setMargins(dp(16), dp(16), dp(16), 0) })
        root.setOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        show(getString(R.string.gemtype_clip_working), working = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard is readable only once the window actually has focus.
        if (hasFocus && !started && GemType.isEnabled) {
            started = true
            fixClipboard()
        }
    }

    private fun fixClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val original = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
        lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { GemTypeSettings.load(this@ClipboardFixActivity) }
                when {
                    config.apiKey.isBlank() -> return@launch show(getString(R.string.gemtype_no_key), button = getString(R.string.gemtype_add_key)) {
                        startActivity(Intent(this@ClipboardFixActivity, GemTypeKeyActivity::class.java))
                        finish()
                    }
                    original == null -> return@launch show(getString(R.string.gemtype_clip_empty))
                    original.length > GemTypePanelController.MAX_CHARS ->
                        return@launch show(getString(R.string.gemtype_too_long_shared, GemTypePanelController.MAX_CHARS))
                }
                val fixed = GemType.client.refine(original!!, Action.FIX, config)
                clipboard.setPrimaryClip(ClipData.newPlainText("GemType", fixed))
                show(getString(R.string.gemtype_clip_done), detail = "“$fixed”", button = getString(R.string.gemtype_clip_undo)) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("GemType", original))
                    show(getString(R.string.gemtype_clip_restored))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiException) {
                show(e.friendly)
            } catch (e: Exception) {
                show(getString(R.string.gemtype_error_generic))
            }
        }
    }

    private fun show(
        message: String,
        detail: String? = null,
        working: Boolean = false,
        button: String? = null,
        onButton: () -> Unit = {},
    ) {
        title.text = message
        this.detail.text = detail
        this.detail.visibility = if (detail == null) View.GONE else View.VISIBLE
        progress.visibility = if (working) View.VISIBLE else View.GONE
        action.visibility = if (button == null) View.GONE else View.VISIBLE
        action.text = button
        action.setOnClickListener { onButton() }
        autoClose?.cancel()
        if (!working) autoClose = lifecycleScope.launch {
            delay(5000)
            finish()
        }
    }
}
