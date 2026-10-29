package com.example.ddlreminder.smartadd

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent

object SpeechSupport {
    fun isAvailable(context: Context): Boolean = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .resolveActivity(context.packageManager) != null

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出一个或多个日程")
    }
}
