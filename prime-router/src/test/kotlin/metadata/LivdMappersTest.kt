package gov.cdc.prime.router.metadata

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import gov.cdc.prime.router.Element
import gov.cdc.prime.router.Schema
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class LivdMappersTest {
    private val livdPath = "./metadata/tables/LIVD-SARS-CoV-2-2021-09-29.csv"
    private val lookupTable = LookupTable.read(livdPath)
    private val codeElement = Element(
        "ordered_test_code",
        tableRef = lookupTable,
        tableColumn = "Test Ordered LOINC Code"
    )

    @Test
    fun `test livdLookup with DeviceId`() {
        val deviceElement = Element("device_id")
        val schema = Schema("testSchema", "testTopic", listOf(deviceElement))
        val mapper = LIVDLookupMapper()

        // Test with a EUA
        val ev1 = mapOf(
            deviceElement.name to
                "BinaxNOW COVID-19 Ag Card 2 Home Test_Abbott Diagnostics Scarborough, Inc._EUA"
        )
        assertThat(mapper.apply(schema, codeElement, ev1).value).isEqualTo("94558-4")

        // Test with a truncated device ID
        val ev1a = mapOf(deviceElement.name to "BinaxNOW COVID-19 Ag Card 2 Home Test_Abb#")
        assertThat(mapper.apply(schema, codeElement, ev1a).value).isEqualTo("94558-4")

        // Test with a ID NOW device id which is has a FDA number
        val ev2 = mapOf(deviceElement.name to "10811877011269_DII")
        assertThat(mapper.apply(schema, codeElement, ev2).value).isEqualTo("94534-5")

        // With GUDID DI
        val ev3 = mapOf(deviceElement.name to "10811877011269")
        assertThat(mapper.apply(schema, codeElement, ev3).value).isEqualTo("94534-5")
    }

    @Test
    fun `test livdLookup with Equipment Model Name`() {
        val modelElement = Element("equipment_model_name")
        val schema = Schema("testSchema", "testTopic", listOf(modelElement))
        val mapper = LIVDLookupMapper()

        // Test with a EUA
        val ev1 = mapOf(modelElement.name to "BinaxNOW COVID-19 Ag Card")
        assertThat(mapper.apply(schema, codeElement, ev1).value).isEqualTo("94558-4")

        // Test with a ID NOW device id
        val ev2 = mapOf(modelElement.name to "ID NOW")
        assertThat(mapper.apply(schema, codeElement, ev2).value).isEqualTo("94534-5")

        // Test for a device ID that has multiple rows and the same test ordered code.
        val ev3 = mapOf(modelElement.name to "1copy COVID-19 qPCR Multi Kit*")
        assertThat(mapper.apply(schema, codeElement, ev3).value).isEqualTo("94531-1")

        // Test for a device ID that has multiple rows and multiple test ordered codes.
        val ev4 = mapOf(modelElement.name to "Alinity i")
        val result = mapper.apply(schema, codeElement, ev4)
        assertThat(result.value).isNull()
        assertThat(result.warnings.isNotEmpty())
    }

    @Test
    fun `test livdLookup for Sofia 2`() {
        val modelElement = Element("equipment_model_name")
        val testPerformedElement = Element("test_performed_code")
        val schema = Schema("testSchema", "testTopic", listOf(modelElement, testPerformedElement))
        val mapper = LIVDLookupMapper()

        mapper.apply(
            schema,
            codeElement,
            mapOf(
                modelElement.name to "Sofia 2 Flu + SARS Antigen FIA*",
                testPerformedElement.name to "95209-3"
            )
        ).let {
            assertThat(it)
                .equals("SARS-CoV+SARS-CoV-2 (COVID-19) Ag [Presence] in Respiratory specimen by Rapid immunoassay")
        }
    }

    @Test
    fun `test livdLookup supplemental table by device_id`() {
        val lookupTable = LookupTable.read("./metadata/tables/LIVD-Supplemental-2021-06-07.csv")
        val codeElement = Element(
            "test_authorized_for_otc",
            tableRef = lookupTable,
            tableColumn = "is_otc"
        )
        val deviceElement = Element("device_id")
        val schema = Schema("testSchema", "testTopic", listOf(codeElement, deviceElement))
        val mapper = LIVDLookupMapper()

        // Test with an FDA device id
        val ev1 = mapOf(deviceElement.name to "10811877011337")
        assertThat(mapper.apply(schema, codeElement, ev1).value).isEqualTo("N")

        // Test with a truncated device ID
        val ev1a = mapOf(deviceElement.name to "BinaxNOW COVID-19 Ag Card 2 Home#")
        assertThat(mapper.apply(schema, codeElement, ev1a).value).isEqualTo("Y")
    }

    @Test
    fun `test livdLookup supplemental table by model`() {
        val lookupTable = LookupTable.read("./metadata/tables/LIVD-Supplemental-2021-06-07.csv")
        val codeElement = Element(
            "test_authorized_for_otc",
            tableRef = lookupTable,
            tableColumn = "is_otc"
        )
        val deviceElement = Element("equipment_model_name")
        val schema = Schema("testSchema", "testTopic", listOf(codeElement, deviceElement))
        val mapper = LIVDLookupMapper()

        // Test with an FDA device id
        val ev1 = mapOf(deviceElement.name to "BinaxNOW COVID-19 Ag Card Home Test")
        assertThat(mapper.apply(schema, codeElement, ev1).value).isEqualTo("N")

        // Test with another
        val ev1a = mapOf(deviceElement.name to "BinaxNOW COVID-19 Ag Card 2 Home Test")
        assertThat(mapper.apply(schema, codeElement, ev1a).value).isEqualTo("Y")
    }

    @Test
    fun `test livdLookup model variation lookup`() {
        // Cue COVID-19 Test does not have an * in the table
        var testModel = "Cue COVID-19 Test"
        var expectedTestOrderedLoinc = "95409-9"
        assertThat(LIVDLookupMapper.lookupByEquipmentModelName(codeElement, testModel, emptyMap()))
            .isEqualTo(expectedTestOrderedLoinc)

        // Add an * to the end of the model name
        assertThat(LIVDLookupMapper.lookupByEquipmentModelName(codeElement, "$testModel*", emptyMap()))
            .isEqualTo(expectedTestOrderedLoinc)

        // Add some other character to fail the lookup
        assertThat(LIVDLookupMapper.lookupByEquipmentModelName(codeElement, "$testModel^", emptyMap()))
            .isNull()

        // Accula SARS-Cov-2 Test does have an * in the table
        testModel = "Accula SARS-Cov-2 Test"
        expectedTestOrderedLoinc = "95409-9"
        assertThat(LIVDLookupMapper.lookupByEquipmentModelName(codeElement, testModel, emptyMap()))
            .isEqualTo(expectedTestOrderedLoinc)

        // Add an * to the end of the model name
        assertThat(LIVDLookupMapper.lookupByEquipmentModelName(codeElement, "$testModel*", emptyMap()))
            .isEqualTo(expectedTestOrderedLoinc)
    }

    @Test
    fun `test value variation`() {
        assertThat(LIVDLookupMapper.getValueVariation("dummy", "*")).isEqualTo("dummy*")
        assertThat(LIVDLookupMapper.getValueVariation("dummy*", "*")).isEqualTo("dummy")
        assertThat(LIVDLookupMapper.getValueVariation("dummy????", "???")).isEqualTo("dummy?")

        assertThat(LIVDLookupMapper.getValueVariation("dummyCaSe", "CASE")).isEqualTo("dummy")
        assertThat(LIVDLookupMapper.getValueVariation("dummyCaSe", "CASE", false)).isEqualTo("dummyCaSeCASE")

        assertFailsWith<IllegalArgumentException>(
            block = {
                LIVDLookupMapper.getValueVariation("dummy", "")
            }
        )

        assertFailsWith<IllegalArgumentException>(
            block = {
                LIVDLookupMapper.getValueVariation("", "*")
            }
        )
    }
}