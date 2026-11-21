package com.vortexx.player

import com.vortexx.player.audio.dsp.OutputProfile
import com.vortexx.player.audio.engine.JhankarRoutes
import com.vortexx.player.audio.engine.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class JhankarRoutesTest {
    @Test
    fun kindsFollowTheDeviceTypeNames() {
        assertEquals(RouteKind.Bluetooth, JhankarRoutes.kindOf("Bluetooth (A2DP)"))
        assertEquals(RouteKind.Bluetooth, JhankarRoutes.kindOf("Bluetooth LE speaker"))
        assertEquals(RouteKind.Wired, JhankarRoutes.kindOf("Wired headphones"))
        assertEquals(RouteKind.Usb, JhankarRoutes.kindOf("USB headset"))
        assertEquals(RouteKind.Hdmi, JhankarRoutes.kindOf("HDMI ARC"))
        assertEquals(RouteKind.Speaker, JhankarRoutes.kindOf("Built-in speaker"))
        assertEquals(RouteKind.Other, JhankarRoutes.kindOf("Other output (99)"))
    }

    @Test
    fun profilesMatchTheOutput() {
        assertEquals(OutputProfile.Tower, JhankarRoutes.defaultProfile(RouteKind.Bluetooth, "Bluetooth (A2DP)"))
        assertEquals(OutputProfile.Tower, JhankarRoutes.defaultProfile(RouteKind.Bluetooth, "Bluetooth LE speaker"))
        assertEquals(OutputProfile.Headphone, JhankarRoutes.defaultProfile(RouteKind.Bluetooth, "Bluetooth LE headset"))
        assertEquals(OutputProfile.Headphone, JhankarRoutes.defaultProfile(RouteKind.Wired, "Wired headset"))
        assertEquals(OutputProfile.Headphone, JhankarRoutes.defaultProfile(RouteKind.Usb, "USB headset"))
        assertEquals(OutputProfile.PremiumStereo, JhankarRoutes.defaultProfile(RouteKind.Speaker, "Built-in speaker"))
        assertEquals(OutputProfile.PremiumStereo, JhankarRoutes.defaultProfile(RouteKind.Hdmi, "HDMI"))
    }
}
