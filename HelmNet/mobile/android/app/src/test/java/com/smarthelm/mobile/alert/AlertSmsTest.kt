package com.smarthelm.mobile.alert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSmsTest {

    @Test
    fun `message names the driver and asks for a call`() {
        val msg = buildAlertSms("Ravi", null)
        assertTrue(msg.contains("Ravi"))
        assertTrue(msg.contains("call them"))
        assertFalse(msg.contains("maps.google.com"))
    }

    @Test
    fun `location becomes a map link with five decimals and dot separators`() {
        val msg = buildAlertSms("Ravi", Pair(12.9715987, 77.5945627))
        assertTrue(msg.endsWith("https://maps.google.com/?q=12.97160,77.59456"))
    }

    @Test
    fun `typical alert fits in two SMS parts`() {
        val msg = buildAlertSms("Ravi Shankar Menon", Pair(-33.8688197, 151.2092955))
        assertTrue(msg.length <= 306)
    }
}
