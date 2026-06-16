package com.multixtream

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast


class MultiXtreamSettings(
    private val plugin: MultiXtreamPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {


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
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {


        val layout =
            LinearLayout(requireContext())

        layout.orientation =
            LinearLayout.VERTICAL

        layout.setPadding(
            40,
            40,
            40,
            40
        )


        val title =
            TextView(requireContext())

        title.text =
            "Multi Xtream - Versione Provider"

        title.textSize = 20f

        layout.addView(title)



        val spinner =
            Spinner(requireContext())


        val names =
            arrayOf(
                "MultiXtream V1",
                "MultiXtream V2"
            )


        spinner.adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                names
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


        save.setOnClickListener {


            sharedPref.edit {

                putString(
                    "multixtream_version",
                    currentVersion
                )
            }


            showToast(
                "Salvato. Riavvia Cloudstream."
            )


            dismiss()
        }


        layout.addView(save)


        return layout
    }
}
