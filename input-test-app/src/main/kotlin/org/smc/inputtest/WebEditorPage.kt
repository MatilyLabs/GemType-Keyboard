/*
 * Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
 */

package org.smc.inputtest

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.webkit.WebView

/**
 * A web editor (a contenteditable body, like the compose box of Gmail, Outlook or Facebook on the web):
 * these apply the keyboard's edits through Chromium, out of step with what the keyboard believes, which
 * is where delete-and-retype composing garbles text.
 *
 *     adb shell am start -n org.smc.inputtest/.MainActivity --es page web
 */
object WebEditorPage : Page {
    override val id = "web"
    override val title = "Web editor"

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(host: Activity): View = WebView(host).apply {
        settings.javaScriptEnabled = true
        loadDataWithBaseURL(
            null,
            """
            <!doctype html><meta name="viewport" content="width=device-width">
            <style>body{font:18px sans-serif;margin:16px}
            #ed{min-height:160px;border:1px solid #888;border-radius:8px;padding:10px}</style>
            <p>Message body (contenteditable)</p><div id="ed" contenteditable="true"></div>
            <p>Plain text: <span id="out"></span></p>
            <script>const ed=document.getElementById('ed'),out=document.getElementById('out');
            ed.addEventListener('input',()=>{out.textContent=ed.innerText});</script>
            """.trimIndent(),
            "text/html", "utf-8", null,
        )
    }
}
