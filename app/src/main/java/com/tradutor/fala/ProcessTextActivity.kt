package com.tradutor.fala

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Tela invisível: recebe o texto selecionado em outro app (menu "Traduzir e falar")
 * ou compartilhado, manda para o serviço de fala e fecha na hora.
 * Você continua no app em que estava, ouvindo a tradução.
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Nenhum texto recebido", Toast.LENGTH_SHORT).show()
        } else {
            SpeakService.translateAndSpeak(this, text)
            Toast.makeText(this, "Traduzindo e falando…", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
