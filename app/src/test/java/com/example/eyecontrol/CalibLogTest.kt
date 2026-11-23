/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibLogTest {

    private fun row(
        pass: Int = 0,
        target: Int = 0,
        idx: Int = 0,
        tMs: Long = 0L,
        kept: Boolean = true,
        luxLog: Double = 1.5,
        pitch: Double = 0.1,
        roll: Double = -0.05,
    ) = CalibLog.Row(
        pass = pass,
        targetIndex = target,
        sampleIndex = idx,
        tMs = tMs,
        targetX = 100f,
        targetY = 200f,
        kept = kept,
        luxLog = luxLog,
        devicePitch = pitch,
        deviceRoll = roll,
        features = DoubleArray(GazeFeatureExtractor.DIM) { it * 0.01 },
    )

    @Test
    fun header_has35Columns() {
        val cols = CalibLog.header().split(',')
        assertEquals(35, cols.size)
        assertEquals("pass", cols[0])
        assertEquals("kept", cols[6])
        assertEquals("f0", cols[10])
        assertEquals("f24", cols[34])
    }

    @Test
    fun buildCsv_rowsAndKeptFlags() {
        val csv = CalibLog.buildCsv(
            listOf(row(idx = 0, tMs = 0L, kept = true), row(idx = 1, tMs = 120L, kept = false)),
        )
        val lines = csv.trim().split('\n')
        assertEquals(3, lines.size)
        val c0 = lines[1].split(',')
        assertEquals("0", c0[0])
        assertEquals("0", c0[2])
        assertEquals("1", c0[6])
        assertEquals("120", lines[2].split(',')[3])
        assertEquals("0", lines[2].split(',')[6])
        assertEquals("1.500", c0[7])
        assertEquals("0.100", c0[8])
        assertEquals("-0.050", c0[9])
        assertEquals("0.000", c0[10])
        assertEquals("0.240", c0[34])
    }

    @Test
    fun buildCsv_nonFiniteBecomesEmpty() {
        val csv = CalibLog.buildCsv(listOf(row(luxLog = Double.NaN, pitch = Double.NaN)))
        val c = csv.trim().split('\n')[1].split(',')
        assertEquals("", c[7])
        assertEquals("", c[8])
    }

    @Test
    fun buildSummaryJson_numbersAndNulls() {
        val json = CalibLog.buildSummaryJson(
            profileId = "sitting",
            passCount = 3,
            screenW = 1080,
            screenH = 2400,
            featureDim = 25,
            inSampleErrorPx = 329.4,
            looErrorPx = 482.1,
            leavePassOutErrorPx = Double.NaN,
            worstPointIndex = 7,
            worstErrorPx = 404.0,
            perPointErrors = listOf(325.0, 224.0),
            lightMinLux = 30.0,
            lightMaxLux = 81.0,
            lightMinLog = 1.0,
            lightMaxLog = 2.0,
            calibratedAtMs = 123456789L,
            rowCount = 378,
            keptRowCount = 270,
        )
        assertTrue(json.contains("\"profile\":\"sitting\""))
        assertTrue(json.contains("\"passCount\":3"))
        assertTrue(json.contains("\"leavePassOutErrorPx\":null"))
        assertTrue(json.contains("\"perPointErrors\":[325.000,224.000]"))
        assertTrue(json.contains("\"rowCount\":378"))
        assertTrue(json.contains("\"keptRowCount\":270"))
    }

    @Test
    fun buildSummaryJson_refusedAndGateFields() {
        val json = CalibLog.buildSummaryJson(
            profileId = "sitting",
            passCount = 1,
            screenW = 1080,
            screenH = 2400,
            featureDim = 25,
            inSampleErrorPx = 100.0,
            looErrorPx = 200.0,
            leavePassOutErrorPx = Double.NaN,
            worstPointIndex = 0,
            worstErrorPx = 150.0,
            perPointErrors = listOf(100.0),
            lightMinLux = 50.0,
            lightMaxLux = 60.0,
            lightMinLog = 1.0,
            lightMaxLog = 2.0,
            calibratedAtMs = 1L,
            rowCount = 126,
            keptRowCount = 90,
            refused = true,
            separationRatio = 0.8,
            separationThreshold = 2.0,
        )
        assertTrue(json.contains("\"refused\":true"))
        assertTrue(json.contains("\"separationRatio\":0.800"))
        assertTrue(json.contains("\"separationThreshold\":2.000"))
    }
}
