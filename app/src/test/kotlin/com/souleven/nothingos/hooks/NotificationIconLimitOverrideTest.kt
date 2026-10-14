package com.souleven.nothingos.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationIconLimitOverrideTest {
    open class Container {
        @JvmField var mIsStaticLayout = true
        @JvmField var mSpeedBumpIndex = 4
        @JvmField var mMaxIcons = 4
        @JvmField var mMaxStaticIcons = 4
        @JvmField var mMaxIconsOnAod = 3
        @JvmField var mMaxIconsOnLockscreen = 4
    }
    class SubContainer : Container()
    class LegacyContainer {
        @JvmField var mMaxStaticIcons = 4
        @JvmField var mMaxIconsOnAod = 3
    }

    private fun assertLimits(c: Container, max: Int, static: Int, aod: Int, lock: Int) {
        assertEquals(max, c.mMaxIcons)
        assertEquals(static, c.mMaxStaticIcons)
        assertEquals(aod, c.mMaxIconsOnAod)
        assertEquals(lock, c.mMaxIconsOnLockscreen)
    }

    @Test fun appliesAllModernAndLegacyLimits() {
        val c = Container()
        NotificationIconLimitOverride(Container::class.java).apply(c, 9)
        assertLimits(c, 9, 9, 9, 9)
    }

    @Test fun repairsDirectResetWithoutCallingSetter() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 9)
        repeat(20) {
            c.mMaxIcons = 4
            c.mMaxStaticIcons = 4
            c.mMaxIconsOnAod = 3
            c.mMaxIconsOnLockscreen = 4
            limiter.apply(c, 9) // before measure/layout/translations
            assertLimits(c, 9, 9, 9, 9)
        }
    }

    @Test fun clearingSettingRestoresStockNotLastOverride() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 9)
        limiter.apply(c, 12)
        limiter.apply(c, null)
        assertLimits(c, 4, 4, 3, 4)
        limiter.apply(c, null)
        assertLimits(c, 4, 4, 3, 4)
    }

    @Test fun restoresLatestSetterValue() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 9)
        c.mMaxIcons = 6 // the real setter is allowed to run first
        limiter.apply(c, 9, setOf("mMaxIcons"))
        limiter.apply(c, null)
        assertLimits(c, 6, 4, 3, 4)
    }

    @Test fun resourceReloadCanSetStockEqualToPreviousOverride() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 9)
        c.mMaxStaticIcons = 9
        c.mMaxIconsOnAod = 5
        c.mMaxIconsOnLockscreen = 6
        limiter.apply(c, 9, setOf("mMaxStaticIcons", "mMaxIconsOnAod", "mMaxIconsOnLockscreen"))
        limiter.apply(c, null)
        assertLimits(c, 4, 9, 5, 6)
    }

    @Test fun clearingAfterUnhookedDirectWriteKeepsLatestStock() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 9)
        c.mMaxIcons = 7
        limiter.apply(c, null)
        assertEquals(7, c.mMaxIcons)
    }

    @Test fun doesNotLimitNotificationShelf() {
        val c = Container().apply { mIsStaticLayout = false }
        NotificationIconLimitOverride(Container::class.java).apply(c, 9)
        assertLimits(c, 4, 4, 3, 4)
    }

    @Test fun switchingToShelfRestoresItsOriginalValues() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 9)
        c.mIsStaticLayout = false
        limiter.apply(c, 9)
        assertLimits(c, 4, 4, 3, 4)
    }

    @Test fun supportsMissingModernFieldsAndInheritedFields() {
        val legacy = LegacyContainer()
        val legacyLimiter = NotificationIconLimitOverride(LegacyContainer::class.java)
        legacyLimiter.apply(legacy, 9)
        assertEquals(9, legacy.mMaxStaticIcons)
        assertEquals(9, legacy.mMaxIconsOnAod)
        legacyLimiter.apply(legacy, null)
        assertEquals(4, legacy.mMaxStaticIcons)
        assertEquals(3, legacy.mMaxIconsOnAod)
        val child = SubContainer()
        NotificationIconLimitOverride(SubContainer::class.java).apply(child, 10)
        assertLimits(child, 10, 10, 10, 10)
        NotificationIconLimitOverride(Any::class.java).apply(Any(), 9)
    }

    @Test fun differentContainersKeepTheirOwnStockLimits() {
        val first = Container()
        val second = Container().apply { mMaxIcons = 6; mMaxIconsOnAod = 5 }
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(first, 9)
        limiter.apply(second, 9)
        limiter.apply(first, null)
        limiter.apply(second, null)
        assertLimits(first, 4, 4, 3, 4)
        assertLimits(second, 6, 4, 5, 4)
    }

    @Test fun zeroIsAnExplicitLimitAndNegativeIsStock() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 0)
        assertLimits(c, 0, 0, 0, 0)
        limiter.apply(c, -1)
        assertLimits(c, 4, 4, 3, 4)
    }

    @Test fun reportsUnhookedResetButNotOrdinarySetterWrites() {
        val c = Container()
        val resets = mutableListOf<String>()
        val limiter = NotificationIconLimitOverride(Container::class.java) { field, old, limit ->
            resets.add("$field:$old->$limit")
        }
        limiter.apply(c, 9)
        assertEquals(emptyList<String>(), resets)
        c.mMaxIcons = 4
        limiter.apply(c, 9, setOf("mMaxIcons"))
        assertEquals(emptyList<String>(), resets)
        c.mMaxIcons = 4
        limiter.apply(c, 9)
        assertEquals(listOf("mMaxIcons:4->9"), resets)
    }

    @Test fun quietNotificationThresholdDoesNotCapExplicitLimit() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 8)
        assertLimits(c, 8, 8, 8, 8)
        assertEquals(-1, c.mSpeedBumpIndex)
        // The legacy SystemUI predicate also checks this independently of maxVisibleIcons.
        val fifthIconOverflows = (c.mSpeedBumpIndex != -1 && 4 >= c.mSpeedBumpIndex) || 4 >= c.mMaxIcons
        assertEquals(false, fifthIconOverflows)
    }

    @Test fun speedBumpSetterAndDirectWritesAreRepaired() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 8)
        repeat(20) {
            c.mSpeedBumpIndex = 4
            limiter.apply(c, 8, setOf("mSpeedBumpIndex"))
            assertEquals(-1, c.mSpeedBumpIndex)
            c.mSpeedBumpIndex = 3
            limiter.apply(c, 8)
            assertEquals(-1, c.mSpeedBumpIndex)
        }
        limiter.apply(c, null)
        assertEquals(3, c.mSpeedBumpIndex)
    }

    @Test fun clearingSettingRestoresQuietThreshold() {
        val c = Container()
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 8)
        limiter.apply(c, null)
        assertEquals(4, c.mSpeedBumpIndex)
        assertLimits(c, 4, 4, 3, 4)
    }

    @Test fun shelfKeepsQuietThresholdAndAllIcons() {
        val c = Container().apply { mIsStaticLayout = false }
        val limiter = NotificationIconLimitOverride(Container::class.java)
        limiter.apply(c, 8)
        assertEquals(4, c.mSpeedBumpIndex)
        assertLimits(c, 4, 4, 3, 4)
        c.mIsStaticLayout = true
        limiter.apply(c, 8)
        c.mIsStaticLayout = false
        limiter.apply(c, 8)
        assertEquals(4, c.mSpeedBumpIndex)
    }

    @Test fun blankSettingDoesNotAlterAnyStockThreshold() {
        val c = Container()
        NotificationIconLimitOverride(Container::class.java).apply(c, null)
        assertEquals(4, c.mSpeedBumpIndex)
        assertLimits(c, 4, 4, 3, 4)
    }

    @Test fun parsesBlankInvalidNegativeAndWhitespace() {
        for (s in listOf("", " ", "abc", "-1", "2147483648")) {
            assertNull(NotificationIconLimitOverride.parseLimit(s))
        }
        assertEquals(9, NotificationIconLimitOverride.parseLimit(" 9 "))
        assertEquals(0, NotificationIconLimitOverride.parseLimit("0"))
    }
}
