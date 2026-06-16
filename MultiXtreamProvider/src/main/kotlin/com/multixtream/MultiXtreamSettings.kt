package com.multixtream

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.core.content.edit
import com.lagradost.cloudstream3.CommonActivity.showToast


class MultiXtreamSettings(
    private val plugin: MultiXtreamPlugin,
    private val sharedPref: SharedPreferences
) : DialogFragment() {


    private var currentVersion =
        sharedPref.getString(
            "multixtream_version",
            "v1"
        ) ?: "v1"


    private var currentPosition =
        when(currentVersion) {
            "v2" -> 1
            else -> 0
        }



    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {


        val layout = LinearLayout(requireContext())


        layout.orientation = LinearLayout.VERTICAL

        layout.setPadding(
            40,
            40,
            40,
            40
        )


        val title = TextView(requireContext())

        title.text =
            "Multi Xtream - Versione"

        title.textSize = 20f


        layout.addView(title)



        val spinner = Spinner(requireContext())


        val versions =
            arrayOf(
                "MultiXtream V1",
                "MultiXtream V2"
            )


        spinner.adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                versions
            )


        spinner.setSelection(
            currentPosition
        )


        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {


                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}


                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    currentPosition = position


                    currentVersion =
                        if(position == 1)
                            "v2"
                        else
                            "v1"
                }
            }



        layout.addView(spinner)



        val save =
            TextView(requireContext())


        save.text =
            "SALVA"

        save.textSize = 18f

        save.setPadding(
            20,
            40,
            20,
            20
        )


        save.setOnClickListener {


            sharedPref.edit {

                putString(
                    "multixtream_version",
                    currentVersion
                )

            }



            AlertDialog.Builder(
                requireContext()
            )
            .setTitle("Riavvio richiesto")
            .setMessage(
                "Versione salvata. Riavvia Cloudstream per applicare il cambio."
            )
            .setPositiveButton(
                "OK"
            ) { _, _ ->

                dismiss()

            }
            .show()
        }


        layout.addView(save)



        return layout
    }
}
