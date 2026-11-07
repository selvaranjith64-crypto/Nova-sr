package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovaService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var running = false
    private var awaitingUntil = 0L
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter.",
        "Why don't scientists trust atoms? They make up everything.",
        "I told my phone I needed space. Now it says storage full.",
        "Why was the computer cold? It left its Windows open."
    )

    private val talk: List<Pair<Regex, List<String>>> = listOf(
        Regex("^(hello|hi|hey|hai|hallo)( there)?$") to listOf("Hello! How can I help you?", "Hi! I'm listening.", "Hey! What can I do for you?"),
        Regex("how are you|how r you|how do you do") to listOf("I'm doing great, thank you for asking. How are you?", "Feeling good and ready to help. How about you?"),
        Regex("^(i am|i'm|im) (fine|good|great|okay|ok|well)") to listOf("That's wonderful to hear!", "Glad you're doing well."),
        Regex("^(i am|i'm|im) (sad|tired|bored|not good|not well|sick)") to listOf("I'm sorry to hear that. I'm here with you. Want me to play some music?", "Take it easy. Should I play something relaxing on Spotify?"),
        Regex("(what('s| is) your name|who are you)") to listOf("I'm Nova, your personal voice assistant."),
        Regex("(who made you|who created you)") to listOf("I was built for you, to help you control your phone with your voice."),
        Regex("(thank you|thanks|thank u)") to listOf("You're welcome!", "Anytime!", "Happy to help."),
        Regex("good morning") to listOf("Good morning! I hope you have a lovely day."),
        Regex("good afternoon") to listOf("Good afternoon! How is your day going?"),
        Regex("good evening") to listOf("Good evening! How can I help?"),
        Regex("good night") to listOf("Good night. Sleep well, and see you tomorrow."),
        Regex("(i love you|love you)") to listOf("That's very kind. I'm always here for you."),
        Regex("(what can you do|help me|what are your commands)") to listOf("I can open apps, make calls, play Spotify, control volume and flashlight, set timers, tell the time, and search the web. Just say Nova first."),
        Regex("(are you there|can you hear me)") to listOf("Yes, I'm here and listening.")
    )

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, "nova")
            .setContentTitle("Nova is listening")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nova", "Nova", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif("Say \"Nova\" and a command")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        muteBeep(false)
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking = false }
            })
            ttsReady = true
        }
    }

    private fun muteBeep(mute: Boolean) {
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { audio.adjustStreamVolume(s, dir, 0) } catch (e: Exception) {}
        }
    }

    private fun startListening() {
        if (!running) return
        if (speaking) { scheduleRestart(700); return }
        muteBeep(true)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(i)
    }

    private fun scheduleRestart(delay: Long = 300) {
        handler.postDelayed({ startListening() }, delay)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onHeard(text)
        scheduleRestart(if (speaking) 900 else 300)
    }

    override fun onError(error: Int) {
        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun onHeard(raw: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotif("Heard: $raw"))
        val t = raw.lowercase().replace(Regex("[.,!?]"), "").trim()
        val now = System.currentTimeMillis()
        val wake = Regex("\\b(nova|noah|nover|novah|nowa|no va|know va)\\b").find(t)
        val cmd = when {
            wake != null -> t.substring(wake.range.last + 1).trim()
            now < awaitingUntil -> t
            else -> return
        }
        awaitingUntil = 0
        if (cmd.isEmpty()) {
            awaitingUntil = now + 9000
            say("Yes?")
            return
        }
        handle(cmd)
        awaitingUntil = System.currentTimeMillis() + 10000
    }

    private fun handle(cmd: String) {
        if (Regex("^(stop|cancel|quiet|be quiet)$").matches(cmd)) { tts?.stop(); speaking = false; return }
        if (Regex("^(goodbye|bye|exit|stop listening|shut down)$").matches(cmd)) {
            say("Goodbye. Talk to you later."); handler.postDelayed({ stopSelf() }, 2500); return
        }
        if (Regex("what('s| is) the time|what time is it").containsMatchIn(cmd)) {
            say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())); return
        }
        if (Regex("today's date|what('s| is) the date|what day is it").containsMatchIn(cmd)) {
            say("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())); return
        }
        if (cmd.contains("joke")) { say(jokes[(System.currentTimeMillis() % jokes.size).toInt()]); return }

        if (Regex("battery").containsMatchIn(cmd)) {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            say("Your battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent."); return
        }
        if (Regex("flash ?light|torch").containsMatchIn(cmd)) {
            setTorch(!Regex("off|close|stop").containsMatchIn(cmd)); return
        }
        if (Regex("volume up|louder|increase volume").containsMatchIn(cmd)) { changeVolume(AudioManager.ADJUST_RAISE); say("Volume up."); return }
        if (Regex("volume down|quieter|lower the volume|decrease volume").containsMatchIn(cmd)) { changeVolume(AudioManager.ADJUST_LOWER); say("Volume down."); return }
        if (Regex("max volume|full volume").containsMatchIn(cmd)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI); say("Maximum volume."); return
        }
        if (Regex("^(pause|pause music|pause the music|stop music|stop the music)$").matches(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return }
        if (Regex("^(resume|resume music|continue|continue music|play music)$").matches(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return }
        if (Regex("next (song|track)|skip").containsMatchIn(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return }
        if (Regex("(previous|last) (song|track)").containsMatchIn(cmd)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return }
        if (Regex("wi-?fi settings|open wi-?fi").containsMatchIn(cmd)) { say("Opening Wi-Fi settings."); launch(Intent(Settings.ACTION_WIFI_SETTINGS)); return }
        if (Regex("bluetooth settings|open bluetooth").containsMatchIn(cmd)) { say("Opening Bluetooth settings."); launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return }
        if (Regex("^(go home|home screen|go to home)$").matches(cmd)) {
            launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)); return
        }

        val timer = Regex("timer (?:for )?(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?").find(cmd)
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?\\s+timer").find(cmd)
        if (timer != null) {
            val n = timer.groupValues[1].toDouble()
            val unit = timer.groupValues[2]
            val ms = (n * when { unit.startsWith("h") -> 3600000 ; unit.startsWith("m") -> 60000 ; else -> 1000 }).toLong()
            val word = when { unit.startsWith("h") -> "hour"; unit.startsWith("m") -> "minute"; else -> "second" }
            val label = (if (n == n.toInt().toDouble()) n.toInt().toString() else n.toString()) + " " + word + (if (n == 1.0) "" else "s")
            handler.postDelayed({
                try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500) } catch (e: Exception) {}
                say("Your $label timer is done.")
            }, ms)
            say("Timer set for $label.")
            return
        }

        for ((rx, replies) in talk) {
            if (rx.containsMatchIn(cmd)) { say(replies[(System.currentTimeMillis() % replies.size).toInt()]); return }
        }

        Regex("^(?:play|put on|start)\\s+(.+?)\\s+(?:on|in|using)\\s+spotify$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^spotify\\s+(?:play\\s+)?(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^play\\s+(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^(?:call|phone|dial|ring)\\s+(.+)$").find(cmd)?.let { callPerson(it.groupValues[1].removePrefix("to ").trim()); return }
        Regex("^(?:open|launch|start|go to)\\s+(.+)$").find(cmd)?.let { openApp(it.groupValues[1]); return }

        val q = Regex("^(?:search(?: for)?|google|look up)\\s+(.+)$").find(cmd)?.groupValues?.get(1) ?: cmd
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        say("Let me search for $q.")
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { say("This phone has no flashlight."); return }
            cm.setTorchMode(id, on)
            say(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            say("I could not control the flashlight.")
        }
    }

    private fun changeVolume(direction: Int) {
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playSpotify(query: String) {
        say("Playing $query on Spotify.")
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        if (!launch(play)) launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
    }

    private fun callPerson(who: String) {
        val digits = who.replace(" ", "")
        val number = if (Regex("^\\+?\\d{3,}$").matches(digits)) digits else findNumber(who)
        if (number == null) { say("I could not find $who in your contacts."); return }
        say("Calling $who.")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val canCall = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun openApp(spoken: String) {
        val name = norm(spoken.removePrefix("the ").removeSuffix(" app"))
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to norm(it.loadLabel(pm).toString()) }
        val match = apps.firstOrNull { it.second == name }
            ?: apps.firstOrNull { it.second.startsWith(name) }
            ?: apps.firstOrNull { name.length >= 3 && it.second.contains(name) }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.first) }
        if (intent != null) {
            say("Opening $spoken.")
            launch(intent)
        } else {
            say("I could not find an app called $spoken.")
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun say(text: String) {
        if (!ttsReady) return
        speaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }
}
