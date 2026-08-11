package com.nankai.smartcane.data.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NearbyRiskWarningDtoTest {
    @Test
    fun camelCaseSourceDevicesAreParsedWithExistingFields() {
        val dto = parseWarning(
            """
            {
              "found": true,
              "warning": {
                "eventId": 12,
                "riskType": "sos",
                "riskLevel": "high",
                "distanceM": 4.5,
                "timestamp": "2026-08-11T10:00:00+00:00",
                "reportCount": 2,
                "sourceDevices": ["cane_001", "cane_002"]
              }
            }
            """.trimIndent()
        )

        assertEquals(12, dto.eventId)
        assertEquals("sos", dto.riskType)
        assertEquals("high", dto.riskLevel)
        assertEquals(4.5, dto.distanceM, 0.0)
        assertEquals("2026-08-11T10:00:00+00:00", dto.timestamp)
        assertEquals(2, dto.reportCount)
        assertEquals(listOf("cane_001", "cane_002"), dto.sourceDevices)
    }

    @Test
    fun snakeCaseSourceDevicesAreParsed() {
        val dto = parseWarning(warningJson("\"source_devices\": [\"cane_003\", \"cane_004\"]"))

        assertEquals(listOf("cane_003", "cane_004"), dto.sourceDevices)
    }

    @Test
    fun missingSourceDevicesReturnsEmptyList() {
        val dto = parseWarning(warningJson())

        assertEquals(emptyList<String>(), dto.sourceDevices)
    }

    @Test
    fun emptySourceDevicesReturnsEmptyList() {
        val dto = parseWarning(warningJson("\"sourceDevices\": []"))

        assertEquals(emptyList<String>(), dto.sourceDevices)
    }

    private fun parseWarning(json: String): NearbyRiskWarningDto {
        val parser = SmartCaneApiClient::class.java.declaredMethods.single { method ->
            method.name == "toNearbyRiskWarningDtoOrNull" &&
                method.parameterTypes.contentEquals(arrayOf(JSONObject::class.java))
        }
        parser.isAccessible = true
        val parsed = parser.invoke(SmartCaneApiClient, JSONObject(json)) as NearbyRiskWarningDto?
        assertNotNull(parsed)
        return requireNotNull(parsed)
    }

    private fun warningJson(sourceDevicesField: String = ""): String = """
        {
          "found": true,
          "warning": {
            "eventId": 12,
            "riskType": "sos",
            "riskLevel": "high",
            "distanceM": 4.5,
            "timestamp": "2026-08-11T10:00:00+00:00",
            "reportCount": 2${if (sourceDevicesField.isEmpty()) "" else ",\n$sourceDevicesField"}
          }
        }
    """.trimIndent()
}
