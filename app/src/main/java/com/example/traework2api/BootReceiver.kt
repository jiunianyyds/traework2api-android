package com.example.traework2api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启接收器（**默认关闭**，由用户在「保活」页显式打开）。
 *
 * ## 为什么默认关
 *
 * 本项目原本刻意不做开机自启（"重启后自己开一次"是原作者的产品决定）。
 * 但真机上最常见的"网关第二天不在了"就是**重启手机**造成的，所以这里把它做成
 * 一个**可选开关**：默认关 = 保持原行为；打开后才有 [onReceive] 的实际动作。
 *
 * ## 参考 WorkBuddy 的三个坑
 *
 *  1. 必须同时接 `LOCKED_BOOT_COMPLETED`（直接启动模式）+ `QUICKBOOT_POWERON`
 *     （部分 ROM 的"快速开机"只发这一个），只接 `BOOT_COMPLETED` 在有些机器上收不到。
 *  2. 收到开机广播时**不能无条件拉起**：用户如果手动停过网关（shouldRun=false），
 *     开机就拉起等于"关了又自己开"。必须先查 [GatewayService.shouldRun]。
 *  3. Android 12+ 从广播里 `startForegroundService` 受后台启动限制，
 *     失败要**安静地放弃**（下次打开 App 会自动启动），不要在广播里抛异常/循环重试。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val app = context.applicationContext
        if (!KeepAlive.isBootAutostartEnabled(app)) {
            Log.i(TAG, "开机广播 $action：未开启开机自启，跳过")
            return
        }
        if (!GatewayService.shouldRun(app)) {
            // 用户主动停止过 —— 尊重它，别"关了又自己开"。
            Log.i(TAG, "开机广播 $action：用户已停止服务，跳过")
            return
        }
        Log.i(TAG, "开机广播 $action：拉起网关服务")
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(Intent(app, GatewayService::class.java))
            } else {
                app.startService(Intent(app, GatewayService::class.java))
            }
        }.onFailure {
            // 后台启动被拦是正常现象（省电/自启动被禁），静默放弃即可。
            Log.w(TAG, "开机拉起失败(忽略): ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}