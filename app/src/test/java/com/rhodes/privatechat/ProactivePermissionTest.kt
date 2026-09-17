package com.rhodes.privatechat

import com.rhodes.privatechat.shared.settings.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息权限解析。
 *
 * 背景：早期版本在首次安装时把所有角色的主动消息权限批量写成"无授权"，147 个内置角色里只有 10 个
 * 会主动联系，玩家几乎收不到主动消息。现在的规则是"玩家显式设置过就听玩家的，否则跟随全局开关"，
 * 这组断言守住这个规则，避免以后有人改回"直接读存值"。
 */
class ProactivePermissionTest {

    @Test
    fun explicitPerOperatorChoiceAlwaysWins() {
        assertTrue(
            "玩家单独打开过 → 全局关掉也要生效",
            SettingsRepository.resolveMsgPermission(explicit = true, storedValue = true, globalDefault = false),
        )
        assertFalse(
            "玩家单独关掉过 → 全局打开也不能打扰他",
            SettingsRepository.resolveMsgPermission(explicit = true, storedValue = false, globalDefault = true),
        )
    }

    @Test
    fun appWrittenLegacyValuesFollowTheGlobalSwitch() {
        // 老版本首装批量写入的 false 没有显式标记，必须跟随全局，否则 137 个角色永远沉默。
        assertTrue(
            SettingsRepository.resolveMsgPermission(explicit = false, storedValue = false, globalDefault = true),
        )
        // 反向同样成立：老版本批量写入的 true 不应绕过全局关闭。
        assertFalse(
            SettingsRepository.resolveMsgPermission(explicit = false, storedValue = true, globalDefault = false),
        )
    }

    @Test
    fun globalSwitchAloneDecidesForUntouchedOperators() {
        listOf(true, false).forEach { global ->
            assertTrue(
                "未单独设置过的角色必须等于全局值(global=$global)",
                SettingsRepository.resolveMsgPermission(explicit = false, storedValue = !global, globalDefault = global) == global,
            )
        }
    }
}
