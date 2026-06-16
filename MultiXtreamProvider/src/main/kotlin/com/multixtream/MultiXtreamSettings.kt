package com.multixtream

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class MultiXtreamSettings(
    private val plugin: MultiXtreamPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {

    private var currentVersion: String = sharedPref?.getString("version", "v1") ?: "v1"
    private var currentVersionPosition: Int = if (currentVersion == "v2") 1 else 0

    private fun View.makeTvCompatible() {
        setPadding(
            paddingLeft + 10,
            paddingTop + 10,
            paddingRight + 10,
            paddingBottom + 10
        )
        background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    private fun getStringRes(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId =
            plugin.resources?.getIdentifier("settings_multixtream", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getStringRes("multixtream_header")

        val labelTw: TextView? = view.findViewByName("label")
        labelTw?.text = getStringRes("multixtream_version_label")

        val versionSpinner: Spinner? = view.findViewByName("version_spinner")
        val versions = arrayOf("v1", "v2")
        val labels = arrayOf(
            getStringRes("multixtream_v1_label") ?: "Versione 1",
            getStringRes("multixtream_v2_label") ?: "Versione 2"
        )

        versionSpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        versionSpinner?.setSelection(currentVersionPosition)

        versionSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                v: View?,
                position: Int,
                id: Long
            ) {
                currentVersion = versions[position]
                currentVersionPosition = position
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                putString("version", currentVersion)
                putInt("versionPosition", currentVersionPosition)
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getStringRes("multixtream_save_title") ?: "Save & Reload")
                .setMessage(getStringRes("multixtream_save_message")
                    ?: "Changes saved. Restart app to apply?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Settings saved. Restart manually to apply.")
                    dismiss()
                }
                .show()
        }
    }

    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            val component = intent?.component

            if (component != null) {
                val restartIntent = Intent.makeRestartActivityTask(component)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            } else {
                showToast("Could not restart app")
            }
        } catch (e: Exception) {
            showToast("Restart error: ${e.message}")
        }
    }
}
