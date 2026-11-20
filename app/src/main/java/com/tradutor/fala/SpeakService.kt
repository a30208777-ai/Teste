package com.tradutor.fala

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Serviço em primeiro plano: traduz com o ML Kit e fala com o TextToSpeech do Android.
 * Por ser "foreground service" (notificação fixa), o Android não o encerra quando você
 * troca de app, abre outra aba/pasta ou apaga a tela — então a fala continua.
 */
class SpeakService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_TRANSLATE_SPEAK = "com.tradutor.fala.TRANSLATE_SPEAK"
        const val ACTION_REPEAT = "com.tradutor.fala.REPEAT"
        const val ACTION_STOP_SPEAKING = "com.tradutor.fala.STOP_SPEAKING"
        const val ACTION_CLOSE = "com.tradutor.fala.CLOSE"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL_ID = "fala"
        private const val NOTIF_ID = 1

        /** A tela principal escuta aqui para mostrar o resultado/estado. */
        @Volatile var listener: ((translated: String?, status: String) -> Unit)? = null

        fun translateAndSpeak(ctx: Context, text: String) = send(ctx, ACTION_TRANSLATE_SPEAK, text)
        fun repeat(ctx: Context) = send(ctx, ACTION_REPEAT)
        fun stopSpeaking(ctx: Context) = send(ctx, ACTION_STOP_SPEAKING)

        private fun send(ctx: Context, action: String, text: String? = null) {
            val i = Intent(ctx, SpeakService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String>? = null // texto, idioma
    private var lastSpoken: Pair<String, String>? = null
    private val translators = mutableMapOf<String, Translator>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this).apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.endsWith("_last") == true) onSpeechFinished()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onSpeechFinished()
                override fun onError(utteranceId: String?, errorCode: Int) = onSpeechFinished()
            })
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TradutorFala:fala")
            .apply { setReferenceCounted(false) }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) {
            report(null, "Erro: o motor de voz (TTS) não iniciou. Instale/ative o \"Serviços de fala do Google\".")
            return
        }
        pendingSpeech?.let { speak(it.first, it.second) }
        pendingSpeech = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Precisa virar foreground logo no início (limite de poucos segundos do Android).
        goForeground("Pronto")

        when (intent?.action) {
            ACTION_TRANSLATE_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) translate(text)
            }
            ACTION_REPEAT -> lastSpoken?.let { speak(it.first, it.second) }
            ACTION_STOP_SPEAKING -> {
                tts?.stop()
                onSpeechFinished()
            }
            ACTION_CLOSE -> {
                tts?.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun translate(text: String) {
        val source = Prefs.source(this)
        val target = Prefs.target(this)
        if (source == target) {
            speak(text, target)
            return
        }
        val key = "$source-$target"
        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }
        update("Preparando idioma (na 1ª vez baixa ~30 MB)…")
        acquireWakeLock()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                update("Traduzindo…")
                translator.translate(text)
                    .addOnSuccessListener { translated -> speak(translated, target) }
                    .addOnFailureListener { e -> fail("Erro ao traduzir: ${e.message}") }
            }
            .addOnFailureListener { e ->
                fail("Erro ao baixar o idioma (precisa de internet na 1ª vez): ${e.message}")
            }
    }

    private fun speak(text: String, lang: String) {
        lastSpoken = text to lang
        report(text, "Falando…")
        goForeground(text, speaking = true)
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = text to lang
            return
        }
        acquireWakeLock()
        val result = engine.setLanguage(Locale.forLanguageTag(lang))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            report(text, "Aviso: voz de \"$lang\" não instalada. Instale em Configurações → Saída de texto para fala.")
        }

        // Textos longos são divididos em partes (limite do TTS).
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val parts = splitText(text, max)
        val stamp = System.currentTimeMillis()
        parts.forEachIndexed { i, part ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (i == parts.lastIndex) "${stamp}_${i}_last" else "${stamp}_$i"
            engine.speak(part, mode, null, id)
        }
    }

    private fun splitText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', ','), max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }

    private fun onSpeechFinished() {
        main.post {
            releaseWakeLock()
            val last = lastSpoken?.first
            report(last, "Pronto")
            goForeground(last ?: "Pronto")
        }
    }

    private fun fail(msg: String) {
        releaseWakeLock()
        report(null, msg)
        goForeground(msg)
    }

    private fun update(status: String) {
        report(null, status)
        goForeground(status)
    }

    private fun report(translated: String?, status: String) {
        main.post { listener?.invoke(translated, status) }
    }

    private fun acquireWakeLock() = wakeLock?.acquire(10 * 60 * 1000L)
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    // ---------- Notificação ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Tradutor falando", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, SpeakService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(text: String, speaking: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (speaking) "Falando tradução" else "Tradutor Fala")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "🔁 Repetir", pending(ACTION_REPEAT, 1))
            .addAction(0, "⏹ Parar", pending(ACTION_STOP_SPEAKING, 2))
            .addAction(0, "✖ Fechar", pending(ACTION_CLOSE, 3))
            .build()
    }

    private fun goForeground(text: String, speaking: Boolean = false) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text, speaking), type)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        translators.values.forEach { it.close() }
        releaseWakeLock()
        super.onDestroy()
    }
}
