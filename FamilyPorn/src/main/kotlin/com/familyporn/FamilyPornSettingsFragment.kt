package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.Plugin
import com.familyporn.BuildConfig

class FamilyPornSettingsFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val id = plugin.resources!!.getIdentifier(
            "bottom_sheet_layout",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        )
        val layout = plugin.resources!!.getLayout(id)
        val view = inflater.inflate(layout, container, false)

        // Save & Restart button
        val saveBtn = view.findView<Button>("save")
        saveBtn.setOnClickListener {
            context?.let { ctx ->
                AlertDialog.Builder(ctx)
                    .setTitle("Restart App?")
                    .setMessage("Save changes and restart the app?")
                    .setPositiveButton("Yes") { _, _ ->
                        restartApp(ctx)
                    }
                    .setNegativeButton("No") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(ctx, "Changes saved", Toast.LENGTH_SHORT).show()
                        dismiss()
                    }
                    .show()
            }
        }

        // Cloudflare bypass button
        val bypassBtn = view.findView<Button>("cf_bypass_btn")
        bypassBtn.setOnClickListener {
            val dialog = CloudflareWebViewDialog(
                targetUrl = "https://familypornhd.com",
                onFinished = { saved ->
                    if (saved) {
                        bypassBtn.text = "✅ CF Cookies Saved – Refresh"
                        Toast.makeText(context, "CF cookies saved!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            dialog.show(parentFragmentManager, "familyporn_cf_bypass")
        }
        // Update button label to show current cookie status
        if (FamilyPornPlugin.cfCookies.isNotBlank()) {
            bypassBtn.text = "✅ CF Cookies Saved – Refresh"
        } else {
            bypassBtn.text = "🛡️ Bypass Cloudflare"
        }

        // Clear CF Cookies button
        val clearBtn = view.findView<Button>("cf_clear_btn")
        clearBtn.setOnClickListener {
            context?.let { ctx ->
                AlertDialog.Builder(ctx)
                    .setTitle("Clear CF Cookies?")
                    .setMessage("This will remove the saved Cloudflare cookies and User-Agent. You will need to bypass Cloudflare again before streaming.")
                    .setPositiveButton("Clear") { _, _ ->
                        val host = FamilyPornPlugin.cfCookieHost
                        if (host.isNotBlank()) {
                            val cm = CookieManager.getInstance()
                            listOf("cf_clearance", "__ddg1_", "__ddg2_", "__cfruid").forEach { name ->
                                cm.setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                            }
                            cm.flush()
                        }
                        FamilyPornPlugin.cfCookies = ""
                        FamilyPornPlugin.cfUserAgent = ""
                        FamilyPornPlugin.cfCookieHost = ""
                        bypassBtn.text = "🛡️ Bypass Cloudflare"
                        Toast.makeText(ctx, "✅ CF Cookies cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .show()
            }
        }

        return view
    }

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }

    private fun restartApp(context: Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
