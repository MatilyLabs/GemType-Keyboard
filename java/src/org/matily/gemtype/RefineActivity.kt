// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.inputmethod.latin.R
import org.smc.inputmethod.indic.setup.GemTypeKeyActivity

/**
 * The GemType sheet for text from other apps: "GemType" in the text-selection menu
 * (ACTION_PROCESS_TEXT, Replace hands the result back when the field is editable) and
 * "Fix with GemType" in the share sheet (ACTION_SEND, Copy / Share). It shows the keyboard
 * panel's own UI, driven by [GemTypePanelController.showText].
 */
internal class RefineActivity : AppCompatActivity(), GemTypePanelController.Host {
    private lateinit var controller: GemTypePanelController
    private var shown = false
    private var text = ""
    private var canReplace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val processText = intent.action == Intent.ACTION_PROCESS_TEXT
        val text = intent.getCharSequenceExtra(
            if (processText) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT,
        )?.toString().orEmpty()
        if (!GemType.isEnabled || text.isBlank()) {
            if (text.isBlank()) Toast.makeText(this, R.string.gemtype_no_text, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        this.text = text
        canReplace = processText && !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        val dp = resources.displayMetrics.density
        val root = FrameLayout(this)
        root.setOnClickListener { finish() } // tap outside the card
        val panel = LayoutInflater.from(this).inflate(R.layout.gemtype_panel, root, false) as GemTypePanelView
        panel.keyboardSized = false
        panel.setPadding(0, 0, 0, 0)
        panel.isClickable = true // keep taps on the card from reaching the root
        val card = GradientDrawable().apply {
            cornerRadius = 24 * dp
            setColor(com.google.android.material.color.MaterialColors.getColor(root, R.attr.colorSurface))
        }
        panel.background = card
        val width = minOf(resources.displayMetrics.widthPixels - (16 * dp).toInt(), (600 * dp).toInt())
        val height = minOf((resources.displayMetrics.heightPixels * 0.7f).toInt(), (460 * dp).toInt())
        root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (8 * dp).toInt()
        })
        // Stay clear of the navigation bar (the window is edge-to-edge on new Android versions).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        controller = GemTypePanelController(panel, this)
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        // First time, or back from the API-key screen: (re)start Fix.
        if (!shown || controller.needsKey) controller.showText(text, canReplace)
        shown = true
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.onHidden()
            controller.destroy()
        }
        super.onDestroy()
    }

    // ---- GemTypePanelController.Host -----------------------------------------------------

    override fun inputConnection(): InputConnection? = null
    override fun isPasswordField() = false
    override fun replaceField(text: CharSequence, before: Int, after: Int) {}

    override fun replaceSelection(text: CharSequence) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text.toString()))
        finish()
    }

    override fun closePanel() = finish()

    override fun openSettings() {
        startActivity(Intent(this, GemTypeKeyActivity::class.java))
    }

    override fun share(text: CharSequence) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.toString())
        val chooser = Intent.createChooser(send, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            chooser.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this, RefineActivity::class.java)),
            )
        }
        startActivity(chooser)
    }
}
