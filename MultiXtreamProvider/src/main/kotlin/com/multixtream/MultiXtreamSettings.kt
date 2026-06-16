package com.multixtream

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MultiXtreamSettings(
    private val plugin: MultiXtreamPlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {

    private var currentVersion: String =
        sharedPref.getString(MultiXtreamPlugin.KEY_SITE_VERSION, "v1") ?: "v1"

    @SuppressLint("DiscouragedApi")
    private fun getDrawable(name: String) =
        ResourcesCompat.getDrawable(
            resources,
            resources.getIdentifier(name, "drawable", requireContext().packageName),
            null
        )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val layoutId = resources.getIdentifier("settings_multixtream", "layout", requireContext().packageName)
        return inflater.inflate(layoutId, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val spinner: Spinner = view.findViewById(
            resources.getIdentifier("version_spinner", "id", requireContext().packageName)
        )
        val saveBtn: ImageButton = view.findViewById(
            resources.getIdentifier("save_btn", "id", requireContext().packageName)
        )

        val versions = arrayOf("v1", "v2")
        val names = arrayOf("Versione 1 (Stabile)", "Versione 2")

        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(versions.indexOf(currentVersion))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentVersion = versions[pos]
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.setOnClickListener {
            sharedPref.edit().putString(MultiXtreamPlugin.KEY_SITE_VERSION, currentVersion).apply()

            AlertDialog.Builder(requireContext())
                .setTitle("Riavvio richiesto")
                .setMessage("Riavviare l'app per applicare le modifiche?")
                .setPositiveButton("Sì") { _, _ -> restartApp() }
                .setNegativeButton("No", null)
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
