package ai.xiaozhi.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object IconSwitchHelper {
    private const val TAG = "IconSwitchHelper"

    // 对应 AppThemeColors 的索引顺序
    // 0: Red, 1: Orange, 2: Amber, 3: Green, 4: Cyan, 5: Blue (Default), 6: Purple
    private val ALIAS_NAMES = listOf(
        "ai.xiaozhi.MainActivityRed",
        "ai.xiaozhi.MainActivityOrange",
        "ai.xiaozhi.MainActivityAmber",
        "ai.xiaozhi.MainActivityGreen",
        "ai.xiaozhi.MainActivityCyan",
        "ai.xiaozhi.MainActivityBlue", // 默认
        "ai.xiaozhi.MainActivityPurple"
    )

    fun changeIcon(context: Context, index: Int) {
        if (index !in ALIAS_NAMES.indices) return

        val targetAlias = ALIAS_NAMES[index]
        val pm = context.packageManager
        val packageName = context.packageName

        // 1. 找到当前启用的 Alias
        var currentEnabledAlias: String? = null
        for (alias in ALIAS_NAMES) {
            val componentName = ComponentName(packageName, alias)
            if (pm.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                currentEnabledAlias = alias
                break
            }
        }

        // 如果没有找到启用的（可能是刚安装，默认Manifest里的那个生效），
        // 或者目标就是当前启用的，则不做操作（除了确保默认Blue被启用）
        if (currentEnabledAlias == targetAlias) {
            Log.d(TAG, "Icon already set to $targetAlias")
            return
        }

        Log.d(TAG, "Switching icon from $currentEnabledAlias to $targetAlias")

        // 2. 启用目标 Alias
        try {
            pm.setComponentEnabledSetting(
                ComponentName(packageName, targetAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable $targetAlias", e)
            return
        }

        // 3. 禁用其他 Alias
        for (alias in ALIAS_NAMES) {
            if (alias != targetAlias) {
                try {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable $alias", e)
                }
            }
        }
    }
}
