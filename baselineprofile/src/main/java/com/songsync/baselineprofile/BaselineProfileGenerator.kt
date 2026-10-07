package com.songsync.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code paths of a cold start and of opening settings, so they are compiled ahead of
 * time on install instead of being interpreted on first use (smoother first launch).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.songsync.app", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Host a group")), 5_000)
        device.findObject(By.desc("Settings"))?.let {
            it.click()
            device.wait(Until.hasObject(By.text("Device name")), 5_000)
            device.pressBack()
        }
    }
}
