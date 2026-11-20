package com.tradutor.fala

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var spinnerFrom: Spinner
    private lateinit var spinnerTo: Spinner
    private lateinit var input: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(
                this, "Sem a notificação, o Android pode cortar a fala em segundo plano.",
                Toast.LENGTH_LONG
            ).show()
        }

    private val speechInput =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val spoken = res.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    input.setText(spoken)
                    SpeakService.translateAndSpeak(this, spoken)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerFrom = findViewById(R.id.spinnerFrom)
        spinnerTo = findViewById(R.id.spinnerTo)
        input = findViewById(R.id.inputText)
        result = findViewById(R.id.resultText)
        status = findViewById(R.id.statusText)

        val names = Languages.list.map { it.second }
        spinnerFrom.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerTo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerFrom.setSelection(Languages.indexOf(Prefs.source(this)))
        spinnerTo.setSelection(Languages.indexOf(Prefs.target(this)))

        val saver = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = saveLangs()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerFrom.onItemSelectedListener = saver
        spinnerTo.onItemSelectedListener = saver

        findViewById<Button>(R.id.btnSwap).setOnClickListener {
            val a = spinnerFrom.selectedItemPosition
            spinnerFrom.setSelection(spinnerTo.selectedItemPosition)
            spinnerTo.setSelection(a)
        }
        findViewById<Button>(R.id.btnTranslate).setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Digite ou fale algo primeiro", Toast.LENGTH_SHORT).show()
            } else {
                saveLangs()
                SpeakService.translateAndSpeak(this, text)
            }
        }
        findViewById<Button>(R.id.btnMic).setOnClickListener { startMic() }
        findViewById<Button>(R.id.btnRepeat).setOnClickListener { SpeakService.repeat(this) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { SpeakService.stopSpeaking(this) }

        askNotificationPermission()
    }

    private fun saveLangs() {
        val from = Languages.list[spinnerFrom.selectedItemPosition].first
        val to = Languages.list[spinnerTo.selectedItemPosition].first
        Prefs.save(this, from, to)
    }

    private fun startMic() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(Prefs.source(this@MainActivity)).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale agora…")
        }
        try {
            speechInput.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Reconhecimento de voz indisponível neste celular", Toast.LENGTH_LONG).show()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        SpeakService.listener = { translated, st ->
            if (translated != null) result.text = translated
            status.text = st
        }
    }

    override fun onStop() {
        SpeakService.listener = null
        super.onStop()
    }
}
