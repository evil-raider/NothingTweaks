package com.souleven.nothingos.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIconLimitsTest {
    private fun assertLimits(raw: String, statusBar: Int?, aod: Int?) {
        assertEquals(statusBar, NotificationIconLimits.statusBar(raw))
        assertEquals(aod, NotificationIconLimits.aod(raw))
    }

    @Test fun usersExistingCommaSeparatedSettingIsSupported() = assertLimits("7,10", 7, 10)
    @Test fun splitValuesAreNotReversed() = assertLimits("10,7", 10, 7)
    @Test fun singleValueAppliesToBothContainers() = assertLimits("7", 7, 7)
    @Test fun whitespaceAroundBothValuesIsAccepted() = assertLimits(" 7 , 10 ", 7, 10)
    @Test fun emptyPreferenceLeavesStockBehaviour() = assertLimits("", null, null)
    @Test fun blankPreferenceLeavesStockBehaviour() = assertLimits("   ", null, null)
    @Test fun minimumAndMaximumExistingValuesAreSupported() = assertLimits("1,50", 1, 50)
    @Test fun maximumSingleValueAppliesToBoth() = assertLimits("50", 50, 50)
    @Test fun zeroIsOutsideTheExistingRange() = assertLimits("0", null, null)
    @Test fun negativeIsOutsideTheExistingRange() = assertLimits("-1", null, null)
    @Test fun tooLargeValueIsOutsideTheExistingRange() = assertLimits("51", null, null)
    @Test fun integerOverflowDoesNotCrash() = assertLimits("99999999999999999999", null, null)
    @Test fun nonNumericPreferenceLeavesStockBehaviour() = assertLimits("invalid", null, null)
    @Test fun missingSecondValueKeepsExistingFirstValueFallback() = assertLimits("7,", 7, 7)
    @Test fun invalidSecondValueKeepsExistingFirstValueFallback() = assertLimits("7,invalid", 7, 7)
    @Test fun outOfRangeSecondValueKeepsExistingFallback() = assertLimits("7,51", 7, 7)
    @Test fun missingFirstValueCanStillConfigureAodAsBefore() = assertLimits(",10", null, 10)
    @Test fun invalidFirstValueCanStillConfigureAodAsBefore() = assertLimits("invalid,10", null, 10)
    @Test fun commaWithoutValuesDoesNotSetEitherLimit() = assertLimits(",", null, null)
    @Test fun decimalNumberIsNotAnIntegerIconCount() = assertLimits("7.5", null, null)
}
