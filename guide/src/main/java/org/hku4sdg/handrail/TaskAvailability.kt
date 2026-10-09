package org.hku4sdg.handrail

import android.content.Context
import android.os.Build

object TaskAvailability {
    /** A known platform capability can hide an unavailable choice; unknown OEMs still use live matching. */
    fun adaptiveBrightnessAvailable(context: Context): Boolean? = runCatching {
        val id = context.resources.getIdentifier("config_automatic_brightness_available", "bool", "android")
        if (id == 0) null else context.resources.getBoolean(id)
    }.getOrNull()
    fun profile(context: Context) = DeviceProfile(Build.VERSION.SDK_INT, Build.HARDWARE, Build.MODEL,
        context.resources.configuration.locales[0].language,
        TaskCatalog.tasks.map { it.packageName }.distinct().mapNotNull { name ->
            runCatching {
                val info = context.packageManager.getPackageInfo(name, 0)
                if (info.applicationInfo?.enabled == true) info.versionName?.let { name to it } else null
            }
                .getOrNull()
        }.toMap())
    fun block(context: Context, task: GuideTask) = TaskCatalog.block(task, profile(context))
    fun experimental(context: Context, task: GuideTask) = TaskCatalog.experimental(task,
        DeviceProfile(Build.VERSION.SDK_INT, Build.HARDWARE, Build.MODEL,
            context.resources.configuration.locales[0].language, emptyMap()))
    fun reason(block: TaskBlock, l: GuideLanguage) = when (block) {
        TaskBlock.SYSTEM -> ExperienceCopy.choose(l, "当前系统尚未验证这项引导。请返回选择其他任务。", "而家嘅系统未验证呢项指引。请返去拣其他任务。", "This system has not been reviewed for this guide. Return to choose another task.")
        TaskBlock.LANGUAGE -> ExperienceCopy.choose(l, "当前系统页面语言尚未验证。应用文字仍可自由切换三语。", "而家嘅系统页面语言未验证。应用文字仍然可以拣三语。", "The system page language has not been reviewed. App text can still use any of the three languages.")
        TaskBlock.MISSING_APP -> ExperienceCopy.choose(l, "目标应用尚未安装或启用，暂时不能开始。", "目标应用未安装或者未启用，暂时唔可以开始。", "The required app is not installed or enabled. This guide cannot start yet.")
        TaskBlock.VERSION -> ExperienceCopy.choose(l, "目标应用版本已改变。这项引导需要重新验证后才能使用。", "目标应用版本变咗。呢项指引要重新验证先可以用。", "The app version has changed. This guide needs to be reviewed again before use.")
    }
}
