package com.monkeycode.privileged

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class MonkeyCodeVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}

class MonkeyCodeVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return MonkeyCodeVoiceInteractionSession(this)
    }
}

class MonkeyCodeVoiceInteractionSession(service: VoiceInteractionSessionService) :
    VoiceInteractionSession(service) {

    override fun onCreate() {
        super.onCreate()
        // 关闭自身 UI，启动全屏助手浮窗
        hide()
        // 启动 MainActivity 或发送广播
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent?.putExtra("voice_assistant_triggered", true)
        context.startActivity(intent)
    }

    override fun onHandleAssist(
        assistData: Bundle?,
        assistStructure: AssistStructure?,
        assistContent: AssistContent?
    ) {
        // 处理 Assist 请求
    }

    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        // 处理截图
    }
}

class MonkeyCodeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // 仅保留 Android 数字助理角色资格所需声明
        listener?.error(SpeechRecognizer.ERROR_NO_MATCH)
    }

    override fun onCancel(listener: Callback?) {
    }

    override fun onStopListening(listener: Callback?) {
    }
}