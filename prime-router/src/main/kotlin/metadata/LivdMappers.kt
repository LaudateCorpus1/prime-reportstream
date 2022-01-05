package gov.cdc.prime.router.metadata

import com.google.common.base.Preconditions
import gov.cdc.prime.router.Element
import gov.cdc.prime.router.ElementResult
import gov.cdc.prime.router.InvalidEquipmentMessage
import gov.cdc.prime.router.Schema

/**
 * This is a lookup mapper specialized for the LIVD table. The LIVD table has multiple columns
 * which could be used for lookup. Different senders send different information, so this mapper
 * incorporates business logic to do this lookup based on the available information.
 *
 * This function uses covid-19 schema elements in the following order:
 * - device_id - From OBX-17.1 and OBX-17.3, may be a FDA GUDID or a textual description
 * - equipment_model_id - From OBX-18.1, matches column 0
 * - test_kit_name_id - matches column M
 * - equipment_model_name - From STRAC, SimpleReport, and many CSVs, matches on column B
 *
 * Example Usage
 *
 *   - name: test_performed_system_version
 *     type: TABLE
 *     table: LIVD-SARS-CoV-2-2021-01-20        # Specific version of the LIVD table to use
 *     tableColumn: LOINC Version ID            # Column in the table to map
 *     mapper: livdLookup()
 *
 */
class LIVDLookupMapper : Mapper {
    override val name = "livdLookup"

    override fun valueNames(element: Element, args: List<String>): List<String> {
        if (args.isNotEmpty())
            error("Schema Error: livdLookup mapper does not expect args")
        // EQUIPMENT_MODEL_NAME is the more stable id so it goes first. Device_id will change as devices change from
        // emergency use to fully authorized status in the LIVD table
        return listOf(EQUIPMENT_MODEL_NAME, DEVICE_ID, EQUIPMENT_MODEL_ID, TEST_KIT_NAME_ID, TEST_PERFORMED_CODE)
    }

    @Deprecated(
        "Use new apply method", level = DeprecationLevel.ERROR,
        replaceWith = ReplaceWith("super.apply(schema, element, allElementValues, recordIndex)")
    )
    override fun apply(element: Element, args: List<String>, values: List<ElementAndValue>): String? {
        throw NotImplementedError()
    }

    override fun apply(schema: Schema, element: Element, allElementValues: Map<String, String>, recordIndex: Int):
        ElementResult {
        val values = getArgumentValues(schema, element, allElementValues, recordIndex)
        // get the test performed code for additional filtering of the test information in case we are
        // dealing with tests that check for more than one type of disease, for example COVID + influenza
        val testPerformedCode = values.firstOrNull { it.element.name == TEST_PERFORMED_CODE }?.value
        val filters: MutableMap<String, String> = mutableMapOf()
        // if the test performed code exists, we should add it to our filtering
        if (!testPerformedCode.isNullOrEmpty()) {
            filters[LIVD_TEST_PERFORMED_CODE] = testPerformedCode
        }
        // carry on as usual
        values.forEach {
            val result = when (it.element.name) {
                DEVICE_ID -> lookupByDeviceId(element, it.value, filters)
                EQUIPMENT_MODEL_ID -> lookupByEquipmentUid(element, it.value, filters)
                TEST_KIT_NAME_ID -> lookupByTestkitId(element, it.value, filters)
                EQUIPMENT_MODEL_NAME -> lookupByEquipmentModelName(element, it.value, filters)
                else -> null
            }
            if (result != null) return ElementResult(result)
        }
        return ElementResult(null).warning(InvalidEquipmentMessage.new())
    }

    companion object {
        private val standard99ELRTypes = listOf("EUA", "DII", "DIT", "DIM", "MNT", "MNI", "MNM")
        const val LIVD_TESTKIT_NAME_ID = "Testkit Name ID"
        const val LIVD_EQUIPMENT_UID = "Equipment UID"
        const val LIVD_MODEL = "Model"
        const val LIVD_TEST_PERFORMED_CODE = "Test Performed LOINC Code"

        const val DEVICE_ID = "device_id"
        const val EQUIPMENT_MODEL_ID = "equipment_model_id"
        const val EQUIPMENT_MODEL_NAME = "equipment_model_name"
        const val TEST_KIT_NAME_ID = "test_kit_name_id"
        const val TEST_PERFORMED_CODE = "test_performed_code"

        /**
         * Does a lookup in the LIVD table based on the element Id
         * @param element the schema element to use for lookups
         * @param deviceId the ID of the test device to lookup LIVD information by
         * @param filters an optional list of additional filters to limit our search by
         * @return a possible String? value based on the lookup
         */
        private fun lookupByDeviceId(
            element: Element,
            deviceId: String,
            filters: Map<String, String>
        ): String? {
            /*
             Dev Note:

             From the LIVD implementation notes says that device_id is not well defined:
              "The Device Identifier (DI) may be a Test Kit Name Identifier or the Equipment (IVD) Identifier
               or a combination of the two. "

             This note discusses many of the forms for the device_id
               https://confluence.hl7.org/display/OO/Proposed+HHS+ELR+Submission+Guidance+using+HL7+v2+Messages#
               ProposedHHSELRSubmissionGuidanceusingHL7v2Messages-DeviceIdentification
             */

            if (deviceId.isBlank()) return null

            // Device Id may be 99ELR type
            val suffix = deviceId.substringAfterLast('_', "")
            if (standard99ELRTypes.contains(suffix)) {
                val value = deviceId.substringBeforeLast('_', "")
                return lookup(element, value, LIVD_TESTKIT_NAME_ID, filters)
                    ?: lookup(element, value, LIVD_EQUIPMENT_UID, filters)
            }

            // truncated 99ELR type
            if (deviceId.endsWith("#")) {
                val value = deviceId.substringBeforeLast('#', "")
                return lookupPrefix(element, value, LIVD_TESTKIT_NAME_ID, filters)
                    ?: lookupPrefix(element, value, LIVD_EQUIPMENT_UID, filters)
            }

            // May be the DI from a GUDID either test-kit or equipment
            return lookup(element, deviceId, LIVD_TESTKIT_NAME_ID, filters)
                ?: lookup(element, deviceId, LIVD_EQUIPMENT_UID, filters)
        }

        /**
         * Does a lookup in the LIVD table based on the element unique identifier
         * @param element the schema element to use for lookups
         * @param value the unique ID of the test device to lookup LIVD information by
         * @param filters an optional list of additional filters to limit our search by
         * @return a possible String? value based on the lookup
         */
        private fun lookupByEquipmentUid(
            element: Element,
            value: String,
            filters: Map<String, String>
        ): String? {
            if (value.isBlank()) return null
            return lookup(element, value, LIVD_EQUIPMENT_UID, filters)
        }

        /**
         * Does a lookup in the LIVD table based on the test kit Id
         * @param element the schema element to use for lookups
         * @param value the test kit ID of the test device to lookup LIVD information by
         * @param filters an optional list of additional filters to limit our search by
         * @return a possible String? value based on the lookup
         */
        private fun lookupByTestkitId(
            element: Element,
            value: String,
            filters: Map<String, String>
        ): String? {
            if (value.isBlank()) return null
            return lookup(element, value, LIVD_TESTKIT_NAME_ID, filters)
        }

        /**
         * Does a lookup in the LIVD table based on the equipment model name
         * @param element the schema element to use for lookups
         * @param value the model name of the test device to lookup LIVD information by
         * @param filters an optional list of additional filters to limit our search by
         * @return a possible String? value based on the lookup
         */
        internal fun lookupByEquipmentModelName(
            element: Element,
            value: String,
            filters: Map<String, String>
        ): String? {
            if (value.isBlank()) return null

            val result = lookup(element, value, LIVD_MODEL, filters)
            // There is an issue with senders setting equipment model names with or without * across all their reports
            // which result in incorrect data sent to receivers.  Check for a model name with or without * just in case.
            return if (result.isNullOrBlank())
                lookup(element, getValueVariation(value, "*"), LIVD_MODEL, filters)
            else result
        }

        /**
         * Gets a variation of a string [value] based on the [suffix].  If the suffix is present in the value
         * then the variation is the value without the suffix, otherwise the variation is the value WITH the suffix.
         * @param ignoreCase set to true to ignore case, false otherwise
         * @return the string variation.
         */
        internal fun getValueVariation(value: String, suffix: String, ignoreCase: Boolean = true): String {
            Preconditions.checkArgument(value.isNotEmpty())
            Preconditions.checkArgument(suffix.isNotEmpty())
            return if (value.endsWith(suffix, ignoreCase)) value.dropLast(suffix.length) else value + suffix
        }

        /**
         * Does the lookup in the LIVD table based on the lookup type and the values passed in
         * @param element the schema element to use for lookups
         * @param onColumn the name of the index column to do the lookup in
         * @param lookup the value to search the index column for
         * @param filters an optional list of additional filters to limit our search by
         * @return a possible String? value based on the lookup
         */
        private fun lookup(
            element: Element,
            lookup: String,
            onColumn: String,
            filters: Map<String, String>
        ): String? {
            val lookupTable = element.tableRef
                ?: error("Schema Error: could not find table '${element.table}'")
            val lookupColumn = element.tableColumn
                ?: error("Schema Error: no tableColumn for element '${element.name}'")
            val searchValues = mutableMapOf(onColumn to lookup)
            searchValues.putAll(filters)
            return lookupTable.FilterBuilder().equalsIgnoreCase(searchValues).findSingleResult(lookupColumn)
        }

        /**
         * Does the lookup in the LIVD table based on the lookup type and the values passed in,
         * by seeing if any values in the index column starts with the index value
         * @param element the schema element to use for lookups
         * @param onColumn the name of the index column to do the lookup in
         * @param lookup the value to search the index column for
         * @param filters an optional list of additional filters to limit our search by
         * @return a possible String? value based on the lookup
         */
        private fun lookupPrefix(
            element: Element,
            lookup: String,
            onColumn: String,
            filters: Map<String, String>
        ): String? {
            val lookupTable = element.tableRef
                ?: error("Schema Error: could not find table '${element.table}'")
            val lookupColumn = element.tableColumn
                ?: error("Schema Error: no tableColumn for element '${element.name}'")
            return lookupTable.FilterBuilder().startsWithIgnoreCase(onColumn, lookup).equalsIgnoreCase(filters)
                .findSingleResult(lookupColumn)
        }
    }
}

/**
 * The obx17 mapper is specific to the LIVD table and the DeviceID field. Do not use in other places.
 *
 * @See <a href=https://confluence.hl7.org/display/OO/Proposed+HHS+ELR+Submission+Guidance+using+HL7+v2+Messages#ProposedHHSELRSubmissionGuidanceusingHL7v2Messages-DeviceIdentification>HHS Submission Guidance</a>Do not use it for other fields and tables.
 */
class Obx17Mapper : Mapper {
    override val name = "obx17"

    override fun valueNames(element: Element, args: List<String>): List<String> {
        if (args.isNotEmpty())
            error("Schema Error: obx17 mapper does not expect args")
        return listOf("equipment_model_name")
    }

    override fun apply(element: Element, args: List<String>, values: List<ElementAndValue>): String? {
        return if (values.isEmpty()) {
            null
        } else {
            val (indexElement, indexValue) = values.first()
            val lookupTable = element.tableRef
                ?: error("Schema Error: could not find table '${element.table}'")
            val indexColumn = indexElement.tableColumn
                ?: error("Schema Error: no tableColumn for element '${indexElement.name}'")
            val testKitNameId = lookupTable.FilterBuilder().equalsIgnoreCase(indexColumn, indexValue)
                .findSingleResult("Testkit Name ID")
            val testKitNameIdType = lookupTable.FilterBuilder().equalsIgnoreCase(indexColumn, indexValue)
                .findSingleResult("Testkit Name ID Type")
            if (testKitNameId != null && testKitNameIdType != null) {
                "${testKitNameId}_$testKitNameIdType"
            } else {
                null
            }
        }
    }
}

/**
 * The obx17Type mapper is specific to the LIVD table and the DeviceID field. Do not use in other places.
 *
 * @See <a href=https://confluence.hl7.org/display/OO/Proposed+HHS+ELR+Submission+Guidance+using+HL7+v2+Messages#ProposedHHSELRSubmissionGuidanceusingHL7v2Messages-DeviceIdentification>HHS Submission Guidance</a>Do not use it for other fields and tables.
 */
class Obx17TypeMapper : Mapper {
    override val name = "obx17Type"

    override fun valueNames(element: Element, args: List<String>): List<String> {
        if (args.isNotEmpty())
            error("Schema Error: obx17Type mapper does not expect args")
        return listOf("equipment_model_name")
    }

    override fun apply(element: Element, args: List<String>, values: List<ElementAndValue>): String? {
        return if (values.isEmpty()) {
            null
        } else {
            val (indexElement, indexValue) = values.first()
            val lookupTable = element.tableRef
                ?: error("Schema Error: could not find table '${element.table}'")
            val indexColumn = indexElement.tableColumn
                ?: error("Schema Error: no tableColumn for element '${indexElement.name}'")
            if (lookupTable.FilterBuilder().equalsIgnoreCase(indexColumn, indexValue)
                .findSingleResult("Testkit Name ID") != null
            ) "99ELR" else null
        }
    }
}