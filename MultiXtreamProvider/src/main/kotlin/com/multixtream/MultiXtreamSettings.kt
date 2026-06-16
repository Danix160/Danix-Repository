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

    private var currentVersion: String =
        sharedPref?.getString(MultiXtreamPlugin.KEY_SITE_VERSION, "v1") ?: "v1"

    private var currentVersionPosition: Int =
        sharedPref?.getInt(MultiXtreamPlugin.KEY_VERSION_POSITION, 0) ?: 0

    @SuppressLint("DiscouragedApi")
    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) findViewById(id) else null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val res = plugin.resources ?: return null
        val layoutId = res.getIdentifier("settings_multixtream", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(layoutId, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionSpinner: Spinner? = view.findViewByName("version_spinner")

        val versions = arrayOf("v1", "v2", "v3")
        val versionNames = arrayOf(
            "Versione 1 (Stabile)",
            "Versione 2",
            "Versione 3 (Sperimentale)"
        )

        versionSpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            versionNames
        )

        versionSpinner?.setSelection(currentVersionPosition)

        versionSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentVersion = versions[pos]
                currentVersionPosition = pos
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                putString(MultiXtreamPlugin.KEY_SITE_VERSION, currentVersion)
                putInt(MultiXtreamPlugin.KEY_VERSION_POSITION, currentVersionPosition)
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Salva & Riavvia")
                .setMessage("Vuoi riavviare l'app per applicare le modifiche?")
                .setPositiveButton("Sì") { _, _ -> restartApp() }
                .setNegativeButton("No") { _, _ -> dismiss() }
                .show()
        }
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        val component = intent?.component ?: return
        val restartIntent = Intent.makeRestartActivityTask(component)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }
}
