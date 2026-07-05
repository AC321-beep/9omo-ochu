package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.core.content.ContextCompat

class FamilyPornSettingsFragment : DialogFragment() {

    @SuppressLint("SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF1A1A2E.toInt())
        }

        // Title
        root.addView(TextView(requireContext()).apply {
            text = "FamilyPorn Settings"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        })

        // Explanation text
        root.addView(TextView(requireContext()).apply {
            text = "Cloudflare Protection:\nIf you see a \"Just a moment\" screen, tap below to open a WebView and solve the challenge. Cookies will be saved automatically."
            textSize = 13f
            setTextColor(0xFFA0A0B0.toInt())
            setPadding(0, 0, 0, 16)
        })

        // ---- Bypass / Refresh text (clickable) ----
        val bypassText = TextView(requireContext()).apply {
            text = if (FamilyPornPlugin.cfCookies.isNotBlank()) "✅ CF Cookies Saved – Refresh" else "🛡️ Bypass Cloudflare"
            textSize = 16f
            setTextColor(0xFF4CAF50.toInt())
            setPadding(0, 0, 0, 8)
            setOnClickListener {
                val dialog = CloudflareWebViewDialog(
                    targetUrl = "https://familypornhd.com",
                    onFinished = { saved ->
                        if (saved) {
                            text = "✅ CF Cookies Saved – Refresh"
                            Toast.makeText(context, "CF cookies saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                dialog.show(parentFragmentManager, "familyporn_cf_bypass")
            }
        }
        root.addView(bypassText)

        // ---- Clear CF Cookies text (clickable) ----
        root.addView(TextView(requireContext()).apply {
            text = "🗑️ Clear CF Cookies"
            textSize = 16f
            setTextColor(0xFFF44336.toInt())
            setPadding(0, 0, 0, 8)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Clear CF Cookies?")
                    .setMessage("This will remove saved cookies.")
                    .setPositiveButton("Clear") { _, _ ->
                        val host = FamilyPornPlugin.cfCookieHost
                        if (host.isNotBlank()) {
                            CookieManager.getInstance().apply {
                                listOf("cf_clearance", "__ddg1_", "__ddg2_", "__cfruid").forEach { name ->
                                    setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                                }
                                flush()
                            }
                        }
                        FamilyPornPlugin.cfCookies = ""
                        FamilyPornPlugin.cfUserAgent = ""
                        FamilyPornPlugin.cfCookieHost = ""
                        bypassText.text = "🛡️ Bypass Cloudflare"
                        Toast.makeText(context, "✅ CF Cookies cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        // ---- Save & Restart text (clickable) ----
        root.addView(TextView(requireContext()).apply {
            text = "💾 Save & Restart"
            textSize = 16f
            setTextColor(0xFF2196F3.toInt())
            setPadding(0, 0, 0, 8)
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Restart App?")
                    .setPositiveButton("Yes") { _, _ ->
                        val pkg = requireContext().packageManager
                        val intent = pkg.getLaunchIntentForPackage(requireContext().packageName)
                        intent?.component?.let {
                            requireContext().startActivity(Intent.makeRestartActivityTask(it))
                            Runtime.getRuntime().exit(0)
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })

        return root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setTitle("FamilyPorn Settings")
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
