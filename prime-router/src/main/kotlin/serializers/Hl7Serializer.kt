package gov.cdc.prime.router.serializers

import ca.uhn.hl7v2.DefaultHapiContext
import ca.uhn.hl7v2.HL7Exception
import ca.uhn.hl7v2.model.Type
import ca.uhn.hl7v2.model.v251.datatype.DR
import ca.uhn.hl7v2.model.v251.datatype.DT
import ca.uhn.hl7v2.model.v251.datatype.TS
import ca.uhn.hl7v2.model.v251.datatype.XTN
import ca.uhn.hl7v2.model.v251.message.ORU_R01
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory
import ca.uhn.hl7v2.parser.EncodingNotSupportedException
import ca.uhn.hl7v2.parser.ModelClassFactory
import ca.uhn.hl7v2.preparser.PreParser
import ca.uhn.hl7v2.util.Terser
import gov.cdc.prime.router.Element
import gov.cdc.prime.router.ElementAndValue
import gov.cdc.prime.router.Hl7Configuration
import gov.cdc.prime.router.InvalidHL7Message
import gov.cdc.prime.router.Mapper
import gov.cdc.prime.router.Metadata
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ResultDetail
import gov.cdc.prime.router.Schema
import gov.cdc.prime.router.SettingsProvider
import gov.cdc.prime.router.Source
import gov.cdc.prime.router.ValueSet
import gov.cdc.prime.router.metadata.LookupTable
import org.apache.logging.log4j.kotlin.Logging
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.TimeZone
import kotlin.math.min

class Hl7Serializer(
    val metadata: Metadata,
    val settings: SettingsProvider
) : Logging {
    data class Hl7Mapping(
        val mappedRows: Map<String, List<String>>,
        val rows: List<RowResult>,
        val errors: List<String>,
        val warnings: List<String>,
    )
    data class RowResult(
        val row: Map<String, List<String>>,
        val errors: List<String>,
        val warnings: List<String>,
    )

    private val hl7SegmentDelimiter: String = "\r"
    private val hapiContext = DefaultHapiContext()
    private val modelClassFactory: ModelClassFactory = CanonicalModelClassFactory(HL7_SPEC_VERSION)
    private val buildVersion: String
    private val buildDate: String
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSSSZZZ")

    init {
        val buildProperties = Properties()
        val propFileStream = this::class.java.classLoader.getResourceAsStream("build.properties")
            ?: error("Could not find the properties file")
        propFileStream.use {
            buildProperties.load(it)
            buildVersion = buildProperties.getProperty("buildVersion", "0.0.0.0")
            buildDate = buildProperties.getProperty("buildDate", "20200101")
        }
        hapiContext.modelClassFactory = modelClassFactory
    }

    /**
     * Write a report with a single item
     */
    fun write(report: Report, outputStream: OutputStream) {
        if (report.itemCount != 1)
            error("Internal Error: multiple item report cannot be written as a single HL7 message")
        val message = createMessage(report, 0)
        outputStream.write(message.toByteArray())
    }

    /**
     * Write a report with BHS and FHS segments and multiple items
     */
    fun writeBatch(
        report: Report,
        outputStream: OutputStream,
    ) {
        // Dev Note: HAPI doesn't support a batch of messages, so this code creates
        // these segments by hand
        outputStream.write(createHeaders(report).toByteArray())
        report.itemIndices.map {
            val message = createMessage(report, it)
            outputStream.write(message.toByteArray())
        }
        outputStream.write(createFooters(report).toByteArray())
    }

    /*
     * Read in a file
     */
    fun convertBatchMessagesToMap(message: String, schema: Schema): Hl7Mapping {
        val mappedRows: MutableMap<String, MutableList<String>> = mutableMapOf()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rowResults = mutableListOf<RowResult>()
        val reg = "[\r\n]".toRegex()
        val cleanedMessage = reg.replace(message, hl7SegmentDelimiter)
        val messageLines = cleanedMessage.split(hl7SegmentDelimiter)
        val nextMessage = StringBuilder()
        var reportNumber = 1

        /**
         * Parse an HL7 [message] from a string.
         */
        fun parseStringMessage(message: String) {
            val parsedMessage = convertMessageToMap(message, schema)
            parsedMessage.errors.forEach {
                errors.add("Report $reportNumber: $it")
            }
            parsedMessage.warnings.forEach {
                warnings.add("Report $reportNumber: $it")
            }
            if (parsedMessage.row.isNotEmpty())
                rowResults.add(parsedMessage)
            parsedMessage.row.forEach { (k, v) ->
                if (!mappedRows.containsKey(k))
                    mappedRows[k] = mutableListOf()

                mappedRows[k]?.addAll(v)
            }
        }

        messageLines.forEach {
            if (it.startsWith("FHS"))
                return@forEach
            if (it.startsWith("BHS"))
                return@forEach
            if (it.startsWith("BTS"))
                return@forEach
            if (it.startsWith("FTS"))
                return@forEach

            if (nextMessage.isNotBlank() && it.startsWith("MSH")) {
                parseStringMessage(nextMessage.toString())
                nextMessage.clear()
                reportNumber++
            }

            if (it.isNotBlank()) {
                nextMessage.append("$it\r")
            }
        }

        // catch the last message
        if (nextMessage.isNotBlank()) {
            parseStringMessage(nextMessage.toString())
        }

        return Hl7Mapping(mappedRows, rowResults, errors, warnings)
    }

    /**
     * Convert an HL7 [message] based on the specified [schema].
     * @returns the resulting data
     */
    fun convertMessageToMap(message: String, schema: Schema): RowResult {
        /**
         * Query the terser and get a value.
         * @param terser the HAPI terser
         * @param terserSpec the HL7 field to fetch as a terser spec
         * @param errors the list of errors for this message decoding
         * @return the value from the HL7 message or an empty string if no value found
         */
        fun queryTerserForValue(
            terser: Terser,
            terserSpec: String,
            errors: MutableList<String>,
        ): String {
            val parsedValue = try {
                terser.get(terserSpec)
            } catch (e: HL7Exception) {
                errors.add("Exception for $terserSpec: ${e.message}")
                null
            }

            return parsedValue ?: ""
        }

        /**
         * Decode answers to AOE questions
         * @param element the element for the AOE question
         * @param terser the HAPI terser
         * @param errors the list of errors for this message decoding
         * @return the value from the HL7 message or an empty string if no value found
         */
        fun decodeAOEQuestion(
            element: Element,
            terser: Terser,
            errors: MutableList<String>
        ): String {
            var value = ""
            val question = element.hl7AOEQuestion!!
            val countObservations = 10
            // todo: map each AOE by the AOE question ID
            for (c in 0 until countObservations) {
                var spec = "/.OBSERVATION($c)/OBX-3-1"
                val questionCode = try {
                    terser.get(spec)
                } catch (e: HL7Exception) {
                    // todo: convert to result detail, maybe
                    errors.add("Exception for $spec: ${e.message}")
                    null
                }
                if (questionCode?.startsWith(question) == true) {
                    spec = "/.OBSERVATION($c)/OBX-5"
                    value = queryTerserForValue(terser, spec, errors)
                }
            }
            return value
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        // key of the map is the column header, list is the values in the column
        val mappedRows: MutableMap<String, String> = mutableMapOf()
        hapiContext.modelClassFactory = modelClassFactory
        val parser = hapiContext.pipeParser
        val reg = "[\r\n]".toRegex()
        val cleanedMessage = reg.replace(message, hl7SegmentDelimiter).trim()
        // if the message is empty, return a row result that warns of empty data
        if (cleanedMessage.isEmpty()) {
            logger.debug("Skipping empty message during parsing")
            return RowResult(emptyMap(), emptyList(), listOf("Cannot parse empty HL7 message"))
        }

        val hapiMsg = try {
            // First check that we have an HL7 message we can parse.  Note some older messages may have
            // only MSH 9-1 and MSH-9-2, or even just MSH-9-1, so we need use those two fields to compare
            val msgType = PreParser.getFields(cleanedMessage, "MSH-9-1", "MSH-9-2")
            when {
                msgType.isNullOrEmpty() || msgType[0] == null -> {
                    errors.add("Missing required HL7 message type field MSH-9")
                    return RowResult(emptyMap(), errors, warnings)
                }
                arrayOf("ORU", "R01") contentEquals msgType -> parser.parse(cleanedMessage)
                else -> {
                    warnings.add("Ignoring unsupported HL7 message type ${msgType.joinToString(",")}")
                    return RowResult(emptyMap(), errors, warnings)
                }
            }
        } catch (e: HL7Exception) {
            logger.error("${e.localizedMessage} ${e.stackTraceToString()}")
            if (e is EncodingNotSupportedException) {
                // This exception error message is a bit cryptic, so let's provide a better one.
                errors.add("Error parsing HL7 message: Invalid HL7 message format")
            } else {
                errors.add("Error parsing HL7 message: ${e.localizedMessage}")
            }
            return RowResult(emptyMap(), errors, warnings)
        }

        try {
            val terser = Terser(hapiMsg)

            // First, extract any data elements from the HL7 message.
            schema.elements.forEach { element ->
                // If there is no value for the key, then initialize it.
                if (!mappedRows.containsKey(element.name)) {
                    mappedRows[element.name] = ""
                }

                // Make a list of all the HL7 primary and alternate fields to look into.
                // Note that the hl7Fields list will be empty if no fields is specified
                val hl7Fields = ArrayList<String>()
                if (!element.hl7Field.isNullOrEmpty()) hl7Fields.add(element.hl7Field)
                if (!element.hl7OutputFields.isNullOrEmpty()) hl7Fields.addAll(element.hl7OutputFields)
                var value = ""
                for (i in 0 until hl7Fields.size) {
                    val hl7Field = hl7Fields[i]
                    value = when {
                        // Decode a phone number
                        element.type == Element.Type.TELEPHONE ||
                            element.type == Element.Type.EMAIL ->
                            decodeHl7TelecomData(terser, element, hl7Field)

                        // Decode a timestamp
                        element.type == Element.Type.DATETIME ||
                            element.type == Element.Type.DATE ->
                            decodeHl7DateTime(terser, element, hl7Field, warnings)

                        // Decode an AOE question
                        hl7Field == "AOE" ->
                            decodeAOEQuestion(element, terser, errors)

                        // Process a CODE type field.  IMPORTANT: Must be checked after AOE as AOE is a CODE field
                        element.type == Element.Type.CODE -> {
                            val rawValue = queryTerserForValue(
                                terser, getTerserSpec(hl7Field), errors
                            )
                            // This verifies the code received is good.  Note the translated value will be the same as
                            // the raw value for valuesets and altvalues
                            try {
                                when {
                                    rawValue.isBlank() -> ""

                                    element.altValues != null && element.altValues.isNotEmpty() ->
                                        element.toNormalized(rawValue, Element.altDisplayToken)

                                    !element.valueSet.isNullOrEmpty() ->
                                        element.toNormalized(rawValue, Element.codeToken)

                                    else -> rawValue
                                }
                            } catch (e: IllegalStateException) {
                                warnings.add("The code $rawValue for field $hl7Field is invalid.")
                                ""
                            }
                        }

                        // No special case here, so get a value from an HL7 field
                        else ->
                            queryTerserForValue(
                                terser, getTerserSpec(hl7Field), errors
                            )
                    }
                    if (value.isNotBlank()) break
                }

                if (value.isNotBlank()) {
                    mappedRows[element.name] = value
                }
            }

            // Second, we process all the element raw values through mappers and defaults.
            schema.elements.forEach {
                val mappedResult = it.processValue(mappedRows, schema)
                mappedRows[it.name] = mappedResult.value ?: ""
                errors.addAll(mappedResult.errors.map { it.detailMsg() })
                warnings.addAll(mappedResult.warnings.map { it.detailMsg() })
            }
        } catch (e: Exception) {
            val msg = "${e.localizedMessage} ${e.stackTraceToString()}"
            logger.error(msg)
            errors.add(msg)
        }

        // Check for required fields now that we are done processing all the fields
        schema.elements.forEach { element ->
            if (!element.isOptional && mappedRows[element.name]!!.isBlank()) {
                errors.add("The Value for ${element.name} for field ${element.hl7Field} is required")
            }
        }

        // convert sets to lists
        val rows = mappedRows.keys.associateWith {
            if (mappedRows[it] != null) listOf(mappedRows[it]!!) else emptyList()
        }

        return RowResult(rows, errors.distinct(), warnings.distinct())
    }

    fun readExternal(
        schemaName: String,
        input: InputStream,
        source: Source
    ): ReadResult {
        val errors = mutableListOf<ResultDetail>()
        val warnings = mutableListOf<ResultDetail>()
        val messageBody = input.bufferedReader().use { it.readText() }
        val schema = metadata.findSchema(schemaName) ?: error("Schema name $schemaName not found")
        val mapping = convertBatchMessagesToMap(messageBody, schema)
        val mappedRows = mapping.mappedRows
        errors.addAll(mapping.errors.map { ResultDetail(ResultDetail.DetailScope.ITEM, "", InvalidHL7Message.new(it)) })
        warnings.addAll(
            mapping.warnings.map {
                ResultDetail(ResultDetail.DetailScope.ITEM, "", InvalidHL7Message.new(it))
            }
        )
        mappedRows.forEach {
            logger.debug("${it.key} -> ${it.value.joinToString()}")
        }
        val report = if (errors.size > 0) null else Report(schema, mappedRows, source, metadata = metadata)
        return ReadResult(report, errors, warnings)
    }

    fun createMessage(report: Report, row: Int): String {

        val hl7Config = report.destination?.translation as? Hl7Configuration?
        val processingId = if (hl7Config?.useTestProcessingMode == true) {
            "T"
        } else {
            "P"
        }
        val message = buildMessage(report, row, processingId)
        hapiContext.modelClassFactory = modelClassFactory
        return hapiContext.pipeParser.encode(message)
    }

    fun buildMessage(
        report: Report,
        row: Int,
        processingId: String = "T",
    ): ORU_R01 {
        val message = ORU_R01()
        message.initQuickstart(MESSAGE_CODE, MESSAGE_TRIGGER_EVENT, processingId)
        // set up our configuration
        val hl7Config = report.destination?.translation as? Hl7Configuration
        val replaceValue = hl7Config?.replaceValue ?: emptyMap()
        val cliaForSender = hl7Config?.cliaForSender ?: emptyMap()
        val suppressQst = hl7Config?.suppressQstForAoe ?: false
        val suppressAoe = hl7Config?.suppressAoe ?: false
        val useOrderingFacilityName = hl7Config?.useOrderingFacilityName
            ?: Hl7Configuration.OrderingFacilityName.STANDARD

        // and we have some fields to suppress
        val suppressedFields = hl7Config
            ?.suppressHl7Fields
            ?.split(",")
            ?.map { it.trim() } ?: emptyList()
        // or maybe we're going to suppress UNK/ASKU for some fields
        val blanksForUnknownFields = hl7Config
            ?.useBlankInsteadOfUnknown
            ?.split(",")
            ?.map { it.lowercase().trim() } ?: emptyList()
        val convertTimestampToDateTimeFields = hl7Config
            ?.convertTimestampToDateTime
            ?.split(",")
            ?.map { it.trim() } ?: emptyList()
        // start processing
        var aoeSequence = 1
        val terser = Terser(message)
        setLiterals(terser)
        // we are going to set up overrides for the elements in the collection if the valueset
        // needs to be overriden
        val reportElements = if (hl7Config?.valueSetOverrides.isNullOrEmpty()) {
            // there are no value set overrides, so we are going to just pass back out the
            // existing collection of schema elements
            report.schema.elements
        } else {
            // we do have valueset overrides, so we need to replace any elements in place
            report.schema.elements.map { elem ->
                // if we're dealing with a code type (which uses a valueset), check if we need to replace
                if (elem.isCodeType) {
                    // is there a replacement valueset in our collection?
                    val replacementValueSet = hl7Config?.valueSetOverrides?.get(elem.valueSet)
                    if (replacementValueSet != null) {
                        // inherit from the base element
                        val newElement = Element(elem.name, valueSet = elem.valueSet, valueSetRef = replacementValueSet)
                        newElement.inheritFrom(elem)
                    } else {
                        elem
                    }
                } else {
                    // this is not a code type, so return the base element
                    elem
                }
            }
        }
        // serialize the rest of the elements
        reportElements.forEach { element ->
            val value = report.getString(row, element.name).let {
                if (it.isNullOrEmpty()) {
                    element.default ?: ""
                } else {
                    it
                }
            }

            if (suppressedFields.contains(element.hl7Field) && element.hl7OutputFields.isNullOrEmpty())
                return@forEach

            if (element.hl7Field == "AOE" && suppressAoe)
                return@forEach

            // some fields need to be blank instead of passing in UNK
            // so in this case we'll just go by field name and set the value to blank
            if (blanksForUnknownFields.contains(element.name) &&
                element.hl7Field != null &&
                (value.equals("ASKU", true) || value.equals("UNK", true))
            ) {
                setComponent(terser, element, element.hl7Field, repeat = null, value = "", report)
                return@forEach
            }

            if (element.hl7OutputFields != null) {
                element.hl7OutputFields.forEach outputFields@{ hl7Field ->
                    if (suppressedFields.contains(hl7Field))
                        return@outputFields
                    if (element.hl7Field != null && element.isTableLookup) {
                        setComponentForTable(terser, element, hl7Field, report, row, hl7Config)
                    } else {
                        setComponent(terser, element, hl7Field, repeat = null, value, report)
                    }
                }
            } else if (element.hl7Field == "AOE" && element.type == Element.Type.NUMBER && !suppressAoe) {
                if (value.isNotBlank()) {
                    val units = report.getString(row, "${element.name}_units")
                    val date = report.getString(row, "specimen_collection_date_time") ?: ""
                    setAOE(terser, element, aoeSequence++, date, value, report, row, units, suppressQst)
                }
            } else if (element.hl7Field == "AOE" && !suppressAoe) {
                if (value.isNotBlank()) {
                    val date = report.getString(row, "specimen_collection_date_time") ?: ""
                    setAOE(terser, element, aoeSequence++, date, value, report, row, suppressQst = suppressQst)
                } else {
                    // if the value is null but we're defaulting
                    if (hl7Config?.defaultAoeToUnknown == true) {
                        val date = report.getString(row, "specimen_collection_date_time") ?: ""
                        setAOE(terser, element, aoeSequence++, date, "UNK", report, row, suppressQst = suppressQst)
                    }
                }
            } else if (element.hl7Field == "ORC-21-1") {
                setOrderingFacilityComponent(terser, rawFacilityName = value, useOrderingFacilityName, report, row)
            } else if (element.hl7Field == "NTE-3") {
                setNote(terser, value)
            } else if (element.hl7Field == "MSH-7") {
                setComponent(
                    terser,
                    element,
                    "MSH-7",
                    repeat = null,
                    value = formatter.format(report.createdDateTime),
                    report
                )
            } else if (element.hl7Field == "MSH-11") {
                setComponent(terser, element, "MSH-11", repeat = null, processingId, report)
            } else if (element.hl7Field != null && element.isTableLookup) {
                setComponentForTable(terser, element, report, row, hl7Config)
            } else if (!element.hl7Field.isNullOrEmpty()) {
                setComponent(terser, element, element.hl7Field, repeat = null, value, report)
            }
        }
        // make sure all fields we're suppressing are empty
        suppressedFields.forEach {
            val pathSpec = formPathSpec(it)
            terser.set(pathSpec, "")
        }

        if (hl7Config?.suppressNonNPI == true &&
            report.getString(row, "ordering_provider_id_authority_type") != "NPI"
        ) {
            // Suppress the ordering_provider_id if not an NPI
            for (hl7Field in listOf("ORC-12-1", "OBR-16-1", "ORC-12-9", "OBR-16-9", "ORC-12-13", "OBR-16-13")) {
                terser.set(formPathSpec(hl7Field), "")
            }
        }

        convertTimestampToDateTimeFields.forEach {
            val pathSpec = formPathSpec(it)
            val tsValue = terser.get(pathSpec)
            if (!tsValue.isNullOrEmpty()) {
                try {
                    val dtFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    val parsedDate = OffsetDateTime.parse(tsValue, formatter).format(dtFormatter)
                    terser.set(pathSpec, parsedDate)
                } catch (_: Exception) {
                    // for now do nothing
                }
            }
        }
        // check for reporting facility overrides
        if (!hl7Config?.reportingFacilityName.isNullOrEmpty()) {
            val pathSpec = formPathSpec("MSH-4-1")
            terser.set(pathSpec, hl7Config?.reportingFacilityName)
        }
        if (!hl7Config?.reportingFacilityId.isNullOrEmpty()) {
            var pathSpec = formPathSpec("MSH-4-2")
            terser.set(pathSpec, hl7Config?.reportingFacilityId)
            if (!hl7Config?.reportingFacilityIdType.isNullOrEmpty()) {
                pathSpec = formPathSpec("MSH-4-3")
                terser.set(pathSpec, hl7Config?.reportingFacilityIdType)
            }
        }

        // check for alt CLIA for out of state testing
        if (!hl7Config?.cliaForOutOfStateTesting.isNullOrEmpty()) {
            val testingStateField = "OBX-24-4"
            val pathSpecTestingState = formPathSpec(testingStateField)
            var originState = terser.get(pathSpecTestingState)

            if (originState.isNullOrEmpty()) {
                val orderingStateField = "ORC-24-4"
                val pathSpecOrderingState = formPathSpec(orderingStateField)
                originState = terser.get(pathSpecOrderingState)
            }

            if (!originState.isNullOrEmpty()) {
                val stateCode = report.destination?.let { settings.findOrganization(it.organizationName)?.stateCode }

                if (!originState.equals(stateCode)) {
                    val sendingFacility = "MSH-4-2"
                    val pathSpecSendingFacility = formPathSpec(sendingFacility)
                    terser.set(pathSpecSendingFacility, hl7Config?.cliaForOutOfStateTesting)
                }
            }
        }

        // get sender id for the record
        val senderID = report.getString(row, "sender_id") ?: ""

        // loop through CLIA resets
        cliaForSender.forEach { (sender, clia) ->
            try {
                // find that sender in the map
                if (sender.equals(senderID.trim(), ignoreCase = true) && !clia.isEmpty()) {
                    // if the sender needs should have a specific CLIA then overwrite the CLIA here
                    val pathSpecSendingFacilityID = formPathSpec("MSH-4-2")
                    terser.set(pathSpecSendingFacilityID, clia)
                }
            } catch (e: Exception) {
                val msg = "${e.localizedMessage} ${e.stackTraceToString()}"
                logger.error(msg)
            }
        }

        replaceValue(replaceValue, terser, message.patienT_RESULT.ordeR_OBSERVATION.observationReps)
        return message
    }

    /**
     * Loop through all [replaceValueMap] key value pairs to fill all non-empty
     * values in the [terser] message. Loop through the number OBX segments sent in
     * [observationRepeats]. Other segments should not repeat.
     */
    private fun replaceValue(
        replaceValueMap: Map<String, String>,
        terser: Terser,
        observationRepeats: Int
    ) {

        // after all values have been set or blanked, check for values that need replacement
        // isNotEmpty returns true only when a value exists. Whitespace only is considered a value
        replaceValueMap.forEach { element ->

            // value can be set as a comma separated list. First split the list .
            val valueList = element.value.split(",").map { it.trim() }
            var value = ""

            valueList.forEach { field ->

                // value could be a literal or a reference to a different HL7 field. When the terser.get fails
                // the assumption is to add the string as a literal
                val valueInMessage = try {
                    val pathSpec = formPathSpec(field)
                    terser.get(pathSpec)
                } catch (e: Exception) {
                    field
                }
                value = value.plus(valueInMessage)
            }

            // OBX segment can repeat. All repeats need to be looped
            if (element.key.length >= 3 && element.key.substring(0, 3) == "OBX") {

                for (i in 0..observationRepeats.minus(1)) {
                    val pathSpec = formPathSpec(element.key, i)
                    val valueInMessage = terser.get(pathSpec) ?: ""
                    if (valueInMessage.isNotEmpty()) {
                        terser.set(pathSpec, value)
                    }
                }
            } else {
                try {
                    val pathSpec = formPathSpec(element.key)
                    val valueInMessage = terser.get(pathSpec)
                    if (valueInMessage.isNotEmpty()) {
                        terser.set(pathSpec, value)
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Set the [terser]'s ORC-21 in accordance to the [useOrderingFacilityName] value.
     */
    fun setOrderingFacilityComponent(
        terser: Terser,
        rawFacilityName: String,
        useOrderingFacilityName: Hl7Configuration.OrderingFacilityName,
        report: Report,
        row: Int,
    ) {
        when (useOrderingFacilityName) {
            // No overrides
            Hl7Configuration.OrderingFacilityName.STANDARD -> {
                setPlainOrderingFacility(terser, rawFacilityName)
            }

            // Override with NCES ID if available
            Hl7Configuration.OrderingFacilityName.NCES -> {
                val ncesId = getSchoolId(report, row, rawFacilityName)
                if (ncesId == null)
                    setPlainOrderingFacility(terser, rawFacilityName)
                else
                    setNCESOrderingFacility(terser, rawFacilityName, ncesId)
            }

            // Override with organization name if available
            Hl7Configuration.OrderingFacilityName.ORGANIZATION_NAME -> {
                val organizationName = report.getString(row, "organization_name") ?: rawFacilityName
                setPlainOrderingFacility(terser, organizationName)
            }
        }
    }

    /**
     * Set the [terser]'s ORC-21-1 with just the [rawFacilityName]
     */
    internal fun setPlainOrderingFacility(
        terser: Terser,
        rawFacilityName: String,
    ) {
        terser.set(formPathSpec("ORC-21-1"), rawFacilityName.trim().take(50))
        // setting a default value for ORC-21-2 per PA's request.
        terser.set(formPathSpec("ORC-21-2"), DEFAULT_ORGANIZATION_NAME_TYPE_CODE)
    }

    /**
     * Set the [terser]'s ORC-21 in accordance to APHL guidance using the [rawFacilityName]
     * and the [ncesId] value.
     */
    internal fun setNCESOrderingFacility(
        terser: Terser,
        rawFacilityName: String,
        ncesId: String
    ) {
        // Implement APHL guidance for ORC-21 when NCES is known
        val facilityName = "${rawFacilityName.trim().take(32)}$NCES_EXTENSION$ncesId"
        terser.set(formPathSpec("ORC-21-1"), facilityName)
        terser.set(formPathSpec("ORC-21-6-1"), "NCES.IES")
        terser.set(formPathSpec("ORC-21-6-2"), "2.16.840.1.113883.3.8589.4.1.119")
        terser.set(formPathSpec("ORC-21-6-3"), "ISO")
        terser.set(formPathSpec("ORC-21-7"), "XX")
        terser.set(formPathSpec("ORC-21-10"), ncesId)
    }

    /**
     * Lookup the NCES id if the site_type is a k12 school
     */
    fun getSchoolId(report: Report, row: Int, rawFacilityName: String): String? {
        // This code only works on the COVID-19 schema or its extensions
        if (!report.schema.containsElement("ordering_facility_name")) return null
        // This recommendation only applies to k-12 schools
        if (report.getString(row, "site_of_care") != "k12") return null

        // NCES lookup is based on school name and zip code
        val zipCode = report.getString(row, "ordering_facility_zip_code", 5) ?: ""
        return ncesLookupTable.value.lookupBestMatch(
            lookupColumn = "NCESID",
            searchColumn = "SCHNAME",
            searchValue = rawFacilityName,
            filterColumn = "LZIP",
            filterValue = zipCode,
            canonicalize = { canonicalizeSchoolName(it) },
            commonWords = listOf("ELEMENTARY", "JUNIOR", "HIGH", "MIDDLE")
        )
    }

    /**
     * Prepare the string for matching by throwing away non-searchable characters and spacing
     */
    internal fun canonicalizeSchoolName(schoolName: String): String {
        val normalizeSchoolType = schoolName
            .uppercase()
            .replace("SCHOOL", "")
            .replace("(H)", "HIGH")
            .replace("(M)", "MIDDLE")
            .replace("K-8", "K8")
            .replace("K-12", "K12")
            .replace("\\(E\\)|ELEM\\.|EL\\.".toRegex(), "ELEMENTARY")
            .replace("ELEM\\s|ELEM$".toRegex(), "ELEMENTARY ")
            .replace("SR HIGH", "SENIOR HIGH")
            .replace("JR HIGH", "JUNIOR HIGH")

        val possesive = normalizeSchoolType
            .replace("\'S", "S")
        val onlyLettersAndSpaces = possesive
            .replace("[^A-Z0-9\\s]".toRegex(), " ")

        // Throw away single letter words
        return onlyLettersAndSpaces
            .split(" ")
            .filter { it.length > 1 }
            .joinToString(" ")
    }

    private fun setComponentForTable(
        terser: Terser,
        element: Element,
        report: Report,
        row: Int,
        config: Hl7Configuration? = null
    ) {
        setComponentForTable(terser, element, element.hl7Field!!, report, row, config)
    }

    private fun setComponentForTable(
        terser: Terser,
        element: Element,
        hl7Field: String,
        report: Report,
        row: Int,
        config: Hl7Configuration? = null
    ) {
        val lookupValues = mutableMapOf<String, String>()
        val pathSpec = formPathSpec(hl7Field)
        val mapper: Mapper? = element.mapperRef
        val args = element.mapperArgs ?: emptyList()
        val valueNames = mapper?.valueNames(element, args)
        report.schema.elements.forEach {
            lookupValues[it.name] = report.getString(row, it.name) ?: element.default ?: ""
        }
        val valuesForMapper = valueNames?.mapNotNull { elementName ->
            val valueElement = report.schema.findElement(elementName) ?: return@mapNotNull null
            val value = lookupValues[elementName] ?: return@mapNotNull null
            ElementAndValue(valueElement, value)
        }
        if (valuesForMapper == null) {
            terser.set(pathSpec, "")
        } else {
            val mappedValue = mapper.apply(element, args, valuesForMapper).value ?: ""
            val truncatedValue = trimAndTruncateValue(mappedValue, hl7Field, config, terser)
            // there are instances where we need to replace the DII value that comes from the LIVD
            // table with an OID that reflects that this is an equipment UID instead. NH raised this
            // as an issue, and the HHS spec on confluence supports their configuration, but we need
            // to isolate out this option, so we don't affect other states we're already in production with
            if (truncatedValue == "DII" && config?.replaceDiiWithOid == true && hl7Field == "OBX-18-3") {
                terser.set(formPathSpec("OBX-18-3"), OBX_18_EQUIPMENT_UID_OID)
                terser.set(formPathSpec("OBX-18-4"), "ISO")
            } else {
                terser.set(pathSpec, truncatedValue)
            }
        }
    }

    /**
     * Set the component to [value] in the [terser] for the passed [hl7Field].
     * [hl7Field] must match the internal [Element] formatting.
     * Set [repeat] for the repeated segment case.
     */
    private fun setComponent(
        terser: Terser,
        element: Element,
        hl7Field: String,
        repeat: Int?,
        value: String,
        report: Report
    ) {
        // Break down the configuration structure
        val hl7Config = report.destination?.translation as? Hl7Configuration?
        val phoneNumberFormatting = hl7Config?.phoneNumberFormatting
            ?: Hl7Configuration.PhoneNumberFormatting.STANDARD
        val pathSpec = formPathSpec(hl7Field, repeat)

        // All components should be trimmed and not blank.
        val trimmedValue = value.trim()
        if (trimmedValue.isBlank()) return

        when (element.type) {
            Element.Type.ID_CLIA -> setCliaComponent(terser, value, hl7Field, hl7Config)
            Element.Type.HD -> setHDComponent(terser, value, pathSpec, hl7Field, hl7Config)
            Element.Type.EI -> setEIComponent(terser, value, pathSpec, hl7Field, hl7Config)
            Element.Type.CODE -> setCodeComponent(terser, value, pathSpec, element.valueSet, element.valueSetRef)
            Element.Type.TELEPHONE -> setTelephoneComponent(terser, value, pathSpec, element, phoneNumberFormatting)
            Element.Type.EMAIL -> setEmailComponent(terser, value, element, hl7Config)
            Element.Type.POSTAL_CODE -> setPostalComponent(terser, value, pathSpec, element)
            Element.Type.DATE, Element.Type.DATETIME -> setDateTimeComponent(
                terser,
                value,
                pathSpec,
                hl7Field,
                hl7Config
            )
            else -> {
                val truncatedValue = trimAndTruncateValue(value, hl7Field, hl7Config, terser)
                terser.set(pathSpec, truncatedValue)
            }
        }
    }

    internal fun setDateTimeComponent(
        terser: Terser,
        value: String,
        pathSpec: String,
        hl7Field: String,
        hl7Config: Hl7Configuration?
    ) {
        // first allow the truncation to happen, so we carry that logic on down
        val truncatedValue = trimAndTruncateValue(value, hl7Field, hl7Config, terser)
        // if we need to convert the offset do it now
        if (hl7Config?.convertPositiveDateTimeOffsetToNegative == true) {
            // we need to convert the offset on date and date time to a negative offset if
            // that is what the receiver needs
            terser.set(pathSpec, Element.convertPositiveOffsetToNegativeOffset(truncatedValue))
        } else {
            terser.set(pathSpec, truncatedValue)
        }
    }

    /**
     * Set the HD component specified by [hl7Field] in [terser] with [value].
     * Truncate appropriately according to [hl7Field] and [hl7Config]
     */
    internal fun setHDComponent(
        terser: Terser,
        value: String,
        pathSpec: String,
        hl7Field: String,
        hl7Config: Hl7Configuration?
    ) {
        val maxLength = getMaxLength(hl7Field, value, hl7Config, terser)
        val hd = Element.parseHD(value, maxLength)
        if (hd.universalId != null && hd.universalIdSystem != null) {
            terser.set("$pathSpec-1", hd.name) // already truncated
            terser.set("$pathSpec-2", hd.universalId)
            terser.set("$pathSpec-3", hd.universalIdSystem)
        } else {
            terser.set(pathSpec, hd.name)
        }
    }

    /**
     * Set the EI component specified by [pathSpec] in [terser] with [value].
     * Truncate appropriately according to [hl7Field] and [hl7Config]
     */
    internal fun setEIComponent(
        terser: Terser,
        value: String,
        pathSpec: String,
        hl7Field: String,
        hl7Config: Hl7Configuration?
    ) {
        val maxLength = getMaxLength(hl7Field, value, hl7Config, terser)
        val ei = Element.parseEI(value)
        if (ei.universalId != null && ei.universalIdSystem != null) {
            terser.set("$pathSpec-1", ei.name.trimAndTruncate(maxLength))
            terser.set("$pathSpec-2", ei.namespace)
            terser.set("$pathSpec-3", ei.universalId)
            terser.set("$pathSpec-4", ei.universalIdSystem)
        } else {
            terser.set(pathSpec, ei.name.trimAndTruncate(maxLength))
        }
    }

    /**
     * Given the pathspec and the value, it will map that back to a valueset, or look up the valueset
     * based on the valueSetName, and fill in the field with the code
     */
    private fun setCodeComponent(
        terser: Terser,
        value: String,
        pathSpec: String,
        valueSetName: String?,
        elementValueSet: ValueSet? = null
    ) {
        if (valueSetName == null) error("Schema Error: Missing valueSet for '$pathSpec'")
        val valueSet = elementValueSet ?: metadata.findValueSet(valueSetName)
            ?: error("Schema Error: Cannot find '$valueSetName'")
        when (valueSet.system) {
            ValueSet.SetSystem.HL7,
            ValueSet.SetSystem.LOINC,
            ValueSet.SetSystem.UCUM,
            ValueSet.SetSystem.SNOMED_CT -> {
                // if it is a component spec then set all sub-components
                if (isField(pathSpec)) {
                    if (value.isNotEmpty()) {
                        // if a value in the valueset replaces something in the standard valueset
                        // we should default to that first, and then we will do all the other
                        // lookups based on that
                        val displayValue = valueSet.values.firstOrNull { v ->
                            v.replaces?.equals(value, true) == true
                        }?.code ?: value
                        terser.set("$pathSpec-1", displayValue)
                        terser.set("$pathSpec-2", valueSet.toDisplayFromCode(displayValue))
                        terser.set("$pathSpec-3", valueSet.toSystemFromCode(displayValue))
                        valueSet.toVersionFromCode(displayValue)?.let {
                            terser.set("$pathSpec-7", it)
                        }
                    }
                } else {
                    terser.set(pathSpec, value)
                }
            }
            else -> {
                terser.set(pathSpec, value)
            }
        }
    }

    /**
     * Set the [value] into the [hl7Field] in the passed in [terser].
     * If [hl7Field] points to a universal HD field, set [value] as the Universal ID field
     * and set 'CLIA' as the Universal ID Type.
     * If [hl7Field] points to CE field, set [value] as the Identifier and 'CLIA' as the Text.
     */
    internal fun setCliaComponent(
        terser: Terser,
        value: String,
        hl7Field: String,
        hl7Config: Hl7Configuration? = null
    ) {
        if (value.isEmpty()) return
        val pathSpec = formPathSpec(hl7Field)
        val maxLength = getMaxLength(hl7Field, value, hl7Config, terser)
        terser.set(pathSpec, value.trimAndTruncate(maxLength))

        when (hl7Field) {
            in HD_FIELDS_UNIVERSAL -> {
                val nextComponent = nextComponent(pathSpec)
                terser.set(nextComponent, "CLIA")
            }
            in CE_FIELDS -> {
                // HD and CE don't have the same format. for the CE field, we have
                // something that sits in the middle between the CLIA and the field
                // that identifies this as a CLIA
                val nextComponent = nextComponent(pathSpec, 2)
                terser.set(nextComponent, "CLIA")
            }
        }
    }

    /**
     * Set the XTN component using [phoneNumberFormatting] to control details
     */
    internal fun setTelephoneComponent(
        terser: Terser,
        value: String,
        pathSpec: String,
        element: Element,
        phoneNumberFormatting: Hl7Configuration.PhoneNumberFormatting
    ) {
        val parts = value.split(Element.phoneDelimiter)
        val areaCode = parts[0].substring(0, 3)
        val local = parts[0].substring(3)
        val country = parts[1]
        val extension = parts[2]
        val localWithDash = if (local.length == 7) "${local.slice(0..2)}-${local.slice(3..6)}" else local

        fun setComponents(pathSpec: String, component1: String) {
            // Note from the HL7 2.5.1 specification about components 1 and 2:
            // This component has been retained for backward compatibility only as of version 2.3.
            // Definition: Specifies the telephone number in a predetermined format that includes an
            // optional extension, beeper number and comment.
            // Format: [NN] [(999)]999-9999[X99999][B99999][C any text]
            // The optional first two digits are the country code. The optional X portion gives an extension.
            // The optional B portion gives a beeper code.
            // The optional C portion may be used for comments like, After 6:00.

            when (phoneNumberFormatting) {
                Hl7Configuration.PhoneNumberFormatting.STANDARD -> {
                    val phoneNumber = "($areaCode)$localWithDash" +
                        if (extension.isNotEmpty()) "X$extension" else ""
                    terser.set(buildComponent(pathSpec, 1), phoneNumber)
                    terser.set(buildComponent(pathSpec, 2), component1)
                }
                Hl7Configuration.PhoneNumberFormatting.ONLY_DIGITS_IN_COMPONENT_ONE -> {
                    terser.set(buildComponent(pathSpec, 1), "$areaCode$local")
                    terser.set(buildComponent(pathSpec, 2), component1)
                }
                Hl7Configuration.PhoneNumberFormatting.AREA_LOCAL_IN_COMPONENT_ONE -> {
                    // Added for backward compatibility
                    terser.set(buildComponent(pathSpec, 1), "($areaCode)$local")
                    terser.set(buildComponent(pathSpec, 2), component1)
                }
            }
            // it's a phone
            terser.set(buildComponent(pathSpec, 3), "PH")
            terser.set(buildComponent(pathSpec, 5), country)
            terser.set(buildComponent(pathSpec, 6), areaCode)
            terser.set(buildComponent(pathSpec, 7), local)
            if (extension.isNotEmpty()) terser.set(buildComponent(pathSpec, 8), extension)
        }

        if (element.nameContains("patient")) {
            // PID-13 is repeatable, which means we could have more than one phone #
            // or email etc, so we need to increment until we get empty for PID-13-2
            var rep = 0
            while (terser.get("/PATIENT_RESULT/PATIENT/PID-13($rep)-2")?.isEmpty() == false) {
                rep += 1
            }
            // if the first component contains an email value, we want to extract the values, and we want to then
            // put the patient phone number into rep 1 for PID-13. this means that the phone number will always
            // appear first in the list of repeats in PID-13
            if (rep > 0 && terser.get("/PATIENT_RESULT/PATIENT/PID-13(0)-2") == "NET") {
                // get the email back out
                val email = terser.get("/PATIENT_RESULT/PATIENT/PID-13(0)-4")
                // clear out the email value now so it's empty for the phone number repeat
                terser.set("/PATIENT_RESULT/PATIENT/PID-13(0)-4", "")
                // overwrite the first repeat
                setComponents("/PATIENT_RESULT/PATIENT/PID-13(0)", "PRN")
                // now write the second repeat
                terser.set("/PATIENT_RESULT/PATIENT/PID-13(1)-2", "NET")
                terser.set("/PATIENT_RESULT/PATIENT/PID-13(1)-3", "Internet")
                terser.set("/PATIENT_RESULT/PATIENT/PID-13(1)-4", email)
            } else {
                setComponents("/PATIENT_RESULT/PATIENT/PID-13($rep)", "PRN")
            }
        } else {
            setComponents(pathSpec, "WPN")
        }
    }

    private fun setEmailComponent(terser: Terser, value: String, element: Element, hl7Config: Hl7Configuration?) {
        // branch on element name. maybe we'll pass through ordering provider email information as well
        if (element.nameContains("patient_email")) {
            // for some state systems, they cannot handle repetition in the PID-13 field, despite what
            // the HL7 specification calls for. In that case, the patient email is not imported. A common
            // workaround is to shove the patient_email into PID-14 which is the business phone
            val truncatedValue = value.trimAndTruncate(XTN_MAX_LENGTHS[3])
            if (hl7Config?.usePid14ForPatientEmail == true) {
                // this is an email address
                terser.set("/PATIENT_RESULT/PATIENT/PID-14-2", "NET")
                // specifies it's an internet telecommunications type
                terser.set("/PATIENT_RESULT/PATIENT/PID-14-3", "Internet")
                terser.set("/PATIENT_RESULT/PATIENT/PID-14-4", truncatedValue)
            } else {
                // PID-13 is repeatable, which means we could have more than one phone #
                // or email etc, so we need to increment until we get empty for PID-13-2
                var rep = 0
                while (terser.get("/PATIENT_RESULT/PATIENT/PID-13($rep)-2")?.isEmpty() == false) {
                    rep += 1
                }
                // this is an email address
                terser.set("/PATIENT_RESULT/PATIENT/PID-13($rep)-2", "NET")
                // specifies it's an internet telecommunications type
                terser.set("/PATIENT_RESULT/PATIENT/PID-13($rep)-3", "Internet")
                terser.set("/PATIENT_RESULT/PATIENT/PID-13($rep)-4", truncatedValue)
            }
        }
    }

    private fun setPostalComponent(terser: Terser, value: String, pathSpec: String, element: Element) {
        val zipFive = element.toFormatted(value, Element.zipFiveToken)
        terser.set(pathSpec, zipFive)
    }

    private fun setAOE(
        terser: Terser,
        element: Element,
        aoeRep: Int,
        date: String,
        value: String,
        report: Report,
        row: Int,
        units: String? = null,
        suppressQst: Boolean = false,
    ) {
        val hl7Config = report.destination?.translation as? Hl7Configuration
        // if the value type is a date, we need to specify that for the AOE questions
        val valueType = when (element.type) {
            Element.Type.DATE -> "DT"
            Element.Type.NUMBER -> "NM"
            Element.Type.CODE -> "CWE"
            else -> "ST"
        }
        terser.set(formPathSpec("OBX-1", aoeRep), (aoeRep + 1).toString())
        terser.set(formPathSpec("OBX-2", aoeRep), valueType)
        val aoeQuestion = element.hl7AOEQuestion
            ?: error("Schema Error: missing hl7AOEQuestion for '${element.name}'")
        setCodeComponent(terser, aoeQuestion, formPathSpec("OBX-3", aoeRep), "covid-19/aoe")

        when (element.type) {
            Element.Type.CODE -> setCodeComponent(terser, value, formPathSpec("OBX-5", aoeRep), element.valueSet)
            Element.Type.NUMBER -> {
                if (element.name != "patient_age") TODO("support other types of AOE numbers")
                if (units == null) error("Schema Error: expected age units")
                setComponent(terser, element, "OBX-5", aoeRep, value, report)
                setCodeComponent(terser, units, formPathSpec("OBX-6", aoeRep), "patient_age_units")
            }
            else -> setComponent(terser, element, "OBX-5", aoeRep, value, report)
        }

        val rawObx19Value = report.getString(row, "test_result_date")
        val obx19Value = if (rawObx19Value != null && hl7Config?.convertPositiveDateTimeOffsetToNegative == true) {
            Element.convertPositiveOffsetToNegativeOffset(rawObx19Value)
        } else {
            rawObx19Value
        }
        terser.set(formPathSpec("OBX-11", aoeRep), "F")
        terser.set(formPathSpec("OBX-14", aoeRep), date)
        // some states want the observation date for the AOE questions as well
        terser.set(formPathSpec("OBX-19", aoeRep), obx19Value)
        terser.set(formPathSpec("OBX-23-7", aoeRep), "XX")
        // many states can't accept the QST datapoint out at the end because it is nonstandard
        // we need to pass this in via the translation configuration
        if (!suppressQst) terser.set(formPathSpec("OBX-29", aoeRep), "QST")
        // all of these values must be set on the OBX AOE's for validation
        terser.set(formPathSpec("OBX-23-1", aoeRep), report.getStringByHl7Field(row, "OBX-23-1"))
        // set to a default value, but look below
        // terser.set(formPathSpec("OBX-23-6", aoeRep), report.getStringByHl7Field(row, "OBX-23-6"))
        terser.set(formPathSpec("OBX-23-10", aoeRep), report.getString(row, "testing_lab_clia"))
        terser.set(formPathSpec("OBX-15", aoeRep), report.getString(row, "testing_lab_clia"))
        terser.set(formPathSpec("OBX-24-1", aoeRep), report.getStringByHl7Field(row, "OBX-24-1"))
        terser.set(formPathSpec("OBX-24-2", aoeRep), report.getStringByHl7Field(row, "OBX-24-2"))
        terser.set(formPathSpec("OBX-24-3", aoeRep), report.getStringByHl7Field(row, "OBX-24-3"))
        terser.set(formPathSpec("OBX-24-4", aoeRep), report.getStringByHl7Field(row, "OBX-24-4"))
        // OBX-24-5 is a postal code as well. pad this for now
        // TODO: come up with a better way to repeat these segments
        terser.set(
            formPathSpec("OBX-24-5", aoeRep),
            report.getStringByHl7Field(row, "OBX-24-5")?.padStart(5, '0')
        )
        terser.set(formPathSpec("OBX-24-9", aoeRep), report.getStringByHl7Field(row, "OBX-24-9"))
        // check for the OBX-23-6 value. it needs to be split apart
        val testingLabIdAssigner = report.getString(row, "testing_lab_id_assigner")
        if (testingLabIdAssigner?.contains("^") == true) {
            val testingLabIdAssignerParts = testingLabIdAssigner.split("^")
            testingLabIdAssignerParts.forEachIndexed { index, s ->
                terser.set(formPathSpec("OBX-23-6-${index + 1}", aoeRep), s)
            }
        }
    }

    private fun setNote(terser: Terser, value: String) {
        if (value.isBlank()) return
        terser.set(formPathSpec("NTE-1"), "1")
        terser.set(formPathSpec("NTE-3"), value)
        terser.set(formPathSpec("NTE-4-1"), "RE")
        terser.set(formPathSpec("NTE-4-2"), "Remark")
        terser.set(formPathSpec("NTE-4-3"), "HL70364")
        terser.set(formPathSpec("NTE-4-7"), HL7_SPEC_VERSION)
    }

    private fun setLiterals(terser: Terser) {
        // Value that NIST requires (although # is not part of 2.5.1)
        terser.set("MSH-12", HL7_SPEC_VERSION)
        terser.set("MSH-15", "NE")
        terser.set("MSH-16", "NE")
        terser.set("MSH-17", "USA")
        terser.set("MSH-18", "UNICODE UTF-8")
        terser.set("MSH-19", "")
        terser.set("MSH-20", "")
        /*
        terser.set("MSH-21-1", "PHLabReport-NoAck")
        terser.set("MSH-21-2", "ELR_Receiver")
        terser.set("MSH-21-3", "2.16.840.1.113883.9.11")
        terser.set("MSH-21-4", "ISO")
         */
        terser.set("SFT-1", SOFTWARE_VENDOR_ORGANIZATION)
        terser.set("SFT-2", buildVersion)
        terser.set("SFT-3", SOFTWARE_PRODUCT_NAME)
        terser.set("SFT-4", buildVersion)
        terser.set("SFT-6", buildDate)
        terser.set("/PATIENT_RESULT/PATIENT/PID-1", "1")
        terser.set("/PATIENT_RESULT/ORDER_OBSERVATION/ORC-1", "RE")
        terser.set("/PATIENT_RESULT/ORDER_OBSERVATION/OBR-1", "1")
        terser.set("/PATIENT_RESULT/ORDER_OBSERVATION/SPECIMEN/SPM-1", "1")
        terser.set("/PATIENT_RESULT/ORDER_OBSERVATION/OBSERVATION/OBX-1", "1")
        terser.set("/PATIENT_RESULT/ORDER_OBSERVATION/OBSERVATION/OBX-2", "CWE")
        terser.set("/PATIENT_RESULT/ORDER_OBSERVATION/OBSERVATION/OBX-23-7", "XX")
    }

    /**
     * Get a new truncation limit accounting for the encoding of HL7 special characters.
     * @param value string value to search for HL7 special characters
     * @param truncationLimit the starting limit
     * @return the new truncation limit or starting limit if no special characters are found
     */
    internal fun getTruncationLimitWithEncoding(value: String, truncationLimit: Int): Int {
        val regex = "[&^~|]".toRegex()
        val endIndex = min(value.length, truncationLimit)
        val matchCount = regex.findAll(value.substring(0, endIndex)).count()

        return if (matchCount > 0) {
            truncationLimit.minus(matchCount.times(2))
        } else {
            truncationLimit
        }
    }

    /**
     * Trim and truncate the [value] according to the rules in [hl7Config] for [hl7Field].
     * [terser] provides hl7 standards
     */
    internal fun trimAndTruncateValue(
        value: String,
        hl7Field: String,
        hl7Config: Hl7Configuration?,
        terser: Terser
    ): String {
        val maxLength = getMaxLength(hl7Field, value, hl7Config, terser)
        return value.trimAndTruncate(maxLength)
    }

    /**
     * Calculate for [hl7Field] and [value] the length to truncate the value according to the
     * truncation rules in [hl7Config]. The [terser] is used to determine the HL7 specification length.
     */
    internal fun getMaxLength(hl7Field: String, value: String, hl7Config: Hl7Configuration?, terser: Terser): Int? {
        // get the fields to truncate
        val hl7TruncationFields = hl7Config
            ?.truncateHl7Fields
            ?.uppercase()
            ?.split(",")
            ?.map { it.trim() }
            ?: emptyList()
        return when {
            // This special case takes into account special rules needed by jurisdiction
            hl7Config?.truncateHDNamespaceIds == true && hl7Field in HD_FIELDS_LOCAL -> {
                getTruncationLimitWithEncoding(value, HD_TRUNCATION_LIMIT)
            }
            // For the fields listed here use the hl7 max length
            hl7Field in hl7TruncationFields -> {
                getHl7MaxLength(hl7Field, terser)
            }
            // In general, don't truncate. The thinking is that
            // 1. the max length of the specification is "normative" not system specific.
            // 2. ReportStream is a conduit and truncation is a loss of information
            // 3. Much of the current HHS guidance implies lengths longer than the 2.5.1 minimums
            // 4. Later hl7 specifications, relax the minimum length requirements
            else -> null
        }
    }

    /**
     * Given the internal field or component specified in [hl7Field], return the maximum string length
     * according to the HL7 specification. The [terser] provides the HL7 specifications
     */
    internal fun getHl7MaxLength(hl7Field: String, terser: Terser): Int? {
        fun getMaxLengthForCompositeType(type: Type, component: Int): Int? {
            val typeName = type.name
            val table = HL7_COMPONENT_MAX_LENGTH[typeName] ?: return null
            return if (component <= table.size) table[component - 1] else null
        }

        // Dev Note: this function is work in progress.
        // It is meant to be a general function for all fields and components,
        // but only has support for the cases of current COVID-19 schema.
        val segmentName = hl7Field.substring(0, 3)
        val segmentSpec = formSegSpec(segmentName)
        val segment = terser.getSegment(segmentSpec)
        val parts = hl7Field.substring(4).split("-").map { it.toInt() }
        val field = segment.getField(parts[0], 0)
        return when (parts.size) {
            // In general, use the values found in the HAPI library for fields
            1 -> segment.getLength(parts[0])
            // use our max-length tables when field and component is specified
            2 -> getMaxLengthForCompositeType(field, parts[1])
            // Add cases for sub-components here
            else -> null
        }
    }

    private fun createHeaders(report: Report): String {
        val sendingApplicationReport = report.getString(0, "sending_application") ?: ""
        val receivingApplicationReport = report.getString(0, "receiving_application") ?: ""
        val receivingFacilityReport = report.getString(0, "receiving_facility") ?: ""

        var sendingAppTruncationLimit: Int? = null
        var receivingAppTruncationLimit: Int? = null
        var receivingFacilityTruncationLimit: Int? = null

        val hl7Config = report.destination?.translation as? Hl7Configuration?
        if (hl7Config?.truncateHDNamespaceIds == true) {
            sendingAppTruncationLimit = getTruncationLimitWithEncoding(sendingApplicationReport, HD_TRUNCATION_LIMIT)
            receivingAppTruncationLimit = getTruncationLimitWithEncoding(
                receivingApplicationReport,
                HD_TRUNCATION_LIMIT
            )
            receivingFacilityTruncationLimit = getTruncationLimitWithEncoding(
                receivingFacilityReport,
                HD_TRUNCATION_LIMIT
            )
        }

        val encodingCharacters = "^~\\&"
        val sendingApp = formatHD(
            Element.parseHD(sendingApplicationReport, sendingAppTruncationLimit)
        )
        val sendingFacility = formatHD(
            Element.parseHD(sendingApplicationReport, sendingAppTruncationLimit)
        )
        val receivingApp = formatHD(
            Element.parseHD(receivingApplicationReport, receivingAppTruncationLimit)
        )
        val receivingFacility = formatHD(
            Element.parseHD(receivingFacilityReport, receivingFacilityTruncationLimit)
        )

        return "FHS|$encodingCharacters|" +
            "$sendingApp|" +
            "$sendingFacility|" +
            "$receivingApp|" +
            "$receivingFacility|" +
            nowTimestamp(hl7Config) +
            hl7SegmentDelimiter +
            "BHS|$encodingCharacters|" +
            "$sendingApp|" +
            "$sendingFacility|" +
            "$receivingApp|" +
            "$receivingFacility|" +
            nowTimestamp(hl7Config) +
            hl7SegmentDelimiter
    }

    private fun createFooters(report: Report): String {
        return "BTS|${report.itemCount}$hl7SegmentDelimiter" +
            "FTS|1$hl7SegmentDelimiter"
    }

    private fun nowTimestamp(hl7Config: Hl7Configuration? = null): String {
        val timestamp = OffsetDateTime.now(ZoneId.systemDefault())
        return if (hl7Config?.convertPositiveDateTimeOffsetToNegative == true) {
            Element.convertPositiveOffsetToNegativeOffset(Element.datetimeFormatter.format(timestamp))
        } else {
            Element.datetimeFormatter.format(timestamp)
        }
    }

    private fun buildComponent(spec: String, component: Int = 1): String {
        if (!isField(spec)) error("Not a component path spec")
        return "$spec-$component"
    }

    private fun isField(spec: String): Boolean {
        // Support the SEG-# and the SEG-#(#) repeat pattern
        val pattern = Regex("[A-Z][A-Z][A-Z]-[0-9]+(?:\\([0-9]+\\))?$")
        return pattern.containsMatchIn(spec)
    }

    private fun nextComponent(spec: String, increment: Int = 1): String {
        val componentPattern = Regex("[A-Z][A-Z][A-Z]-[0-9]+-([0-9]+)$")
        componentPattern.find(spec)?.groups?.get(1)?.let {
            val nextComponent = it.value.toInt() + increment
            return spec.replaceRange(it.range, nextComponent.toString())
        }
        val subComponentPattern = Regex("[A-Z][A-Z][A-Z]-[0-9]+-[0-9]+-([0-9]+)$")
        subComponentPattern.find(spec)?.groups?.get(1)?.let {
            val nextComponent = it.value.toInt() + increment
            return spec.replaceRange(it.range, nextComponent.toString())
        }
        error("Did match on component or subcomponent")
    }

    internal fun formPathSpec(spec: String, rep: Int? = null): String {
        val segment = spec.substring(0, 3)
        val components = spec.substring(3)
        val segmentSpec = formSegSpec(segment, rep)
        return "$segmentSpec$components"
    }

    internal fun formSegSpec(segment: String, rep: Int? = null): String {
        val repSpec = rep?.let { "($rep)" } ?: ""
        return when (segment) {
            "OBR" -> "/PATIENT_RESULT/ORDER_OBSERVATION/OBR"
            "ORC" -> "/PATIENT_RESULT/ORDER_OBSERVATION/ORC"
            "SPM" -> "/PATIENT_RESULT/ORDER_OBSERVATION/SPECIMEN/SPM"
            "PID" -> "/PATIENT_RESULT/PATIENT/PID"
            "OBX" -> "/PATIENT_RESULT/ORDER_OBSERVATION/OBSERVATION$repSpec/OBX"
            "NTE" -> "/PATIENT_RESULT/ORDER_OBSERVATION/OBSERVATION/NTE"
            else -> segment
        }
    }

    private fun formatHD(hdFields: Element.HDFields, separator: String = "^"): String {
        return if (hdFields.universalId != null && hdFields.universalIdSystem != null) {
            "${hdFields.name}$separator${hdFields.universalId}$separator${hdFields.universalIdSystem}"
        } else {
            hdFields.name
        }
    }

    private fun formatEI(eiFields: Element.EIFields, separator: String = "^"): String {
        return if (eiFields.namespace != null && eiFields.universalId != null && eiFields.universalIdSystem != null) {
            "${eiFields.name}$separator${eiFields.namespace}" +
                "$separator${eiFields.universalId}$separator${eiFields.universalIdSystem}"
        } else {
            eiFields.name
        }
    }

    /**
     * Get a phone number from an XTN (e.g. phone number) field of an HL7 message.
     * @param terser the HL7 terser
     * @param element the element to decode
     * @return the phone number or empty string
     */
    internal fun decodeHl7TelecomData(terser: Terser, element: Element, hl7Field: String): String {

        /**
         * Extract a phone number from a value [xtnValue] of an XTN HL7 field.
         * @return a normalized phone number or empty if no phone number was found
         */
        fun getTelecomValue(xtnValue: Type): String {
            var strValue = ""
            if (xtnValue is XTN) {
                when (element.type) {
                    Element.Type.TELEPHONE -> {
                        // If we have an area code or local number then let's use the new fields, otherwise try the deprecated field
                        if (!xtnValue.areaCityCode.isEmpty || !xtnValue.localNumber.isEmpty) {
                            // If the phone number type is specified then make sure it is a phone, otherwise assume it is.
                            if (xtnValue.telecommunicationEquipmentType.isEmpty ||
                                xtnValue.telecommunicationEquipmentType.valueOrEmpty == "PH"
                            ) {
                                strValue = "${xtnValue.areaCityCode.value ?: ""}${xtnValue.localNumber.value ?: ""}:" +
                                    "${xtnValue.countryCode.value ?: ""}:${xtnValue.extension.value ?: ""}"
                            }
                        } else if (!xtnValue.telephoneNumber.isEmpty) {
                            strValue = element.toNormalized(xtnValue.telephoneNumber.valueOrEmpty)
                        }
                    }
                    Element.Type.EMAIL -> {
                        if (xtnValue.telecommunicationEquipmentType.isEmpty ||
                            xtnValue.telecommunicationEquipmentType.valueOrEmpty == "Internet"
                        ) {
                            strValue = element.toNormalized(xtnValue.emailAddress.valueOrEmpty)
                        }
                    }
                    else -> error("${element.type} is unsupported to decode telecom data.")
                }
            }
            return strValue
        }

        var telecomValue = ""

        // Get the field values by going through the terser segment.  This method gives us an
        // array with a maximum number of repetitions, but it may return multiple array elements even if
        // there is no data
        val fieldParts = getTerserSpec(hl7Field).split("-")
        if (fieldParts.size > 1) {
            val segment = terser.getSegment(fieldParts[0])
            val fieldNumber = fieldParts[1].toIntOrNull()
            if (segment != null && fieldNumber != null) {
                segment.getField(fieldNumber)?.forEach {
                    // The first phone number wins
                    if (telecomValue.isBlank()) {
                        telecomValue = getTelecomValue(it)
                    }
                }
            }
        }

        return telecomValue
    }

    /**
     * Get a date time from a TS date time field of an HL7 message.
     * @param terser the HL7 terser
     * @param element the element to decode
     * @param warnings the list of warnings
     * @return the date time or empty string
     */
    internal fun decodeHl7DateTime(
        terser: Terser,
        element: Element,
        hl7Field: String,
        warnings: MutableList<String>
    ): String {
        var valueString = ""
        val fieldParts = getTerserSpec(hl7Field).split("-")
        if (fieldParts.size > 1) {
            val segment = terser.getSegment(fieldParts[0])
            val fieldNumber = fieldParts[1].toIntOrNull()
            if (segment != null && fieldNumber != null) {
                var dtm: Instant? = null
                var rawValue = ""
                when (val value = segment.getField(fieldNumber, 0)) {
                    // Timestamp
                    is TS -> {
                        // If the offset was not specified then set the timezone to UTC instead of the system default
                        // -99 is the value returned from HAPI when no offset is specified
                        if (value.time?.gmtOffset == -99) {
                            val cal = value.time?.valueAsCalendar
                            cal?.let { it.timeZone = TimeZone.getTimeZone("GMT") }
                            dtm = cal?.toInstant()
                        } else dtm = value.time?.valueAsDate?.toInstant()
                        rawValue = value.toString()
                    }
                    // Date range. For getting a date time, use the start of the range
                    is DR -> {
                        if (value.rangeStartDateTime?.time?.gmtOffset == -99) {
                            val cal = value.rangeStartDateTime?.time?.valueAsCalendar
                            cal?.let { it.timeZone = TimeZone.getTimeZone("GMT") }
                            dtm = cal?.toInstant()
                        } else dtm = value.rangeStartDateTime?.time?.valueAsDate?.toInstant()
                        rawValue = value.toString()
                    }
                    is DT -> {
                        dtm = LocalDate.of(value.year, value.month, value.day)
                            .atStartOfDay(ZoneId.systemDefault()).toInstant()
                        rawValue = value.toString()
                    }
                }

                dtm?.let {
                    // Now check to see if we have all the precision we want
                    when (element.type) {
                        Element.Type.DATETIME -> {
                            valueString = DateTimeFormatter.ofPattern(Element.datetimePattern)
                                .format(OffsetDateTime.ofInstant(dtm, ZoneId.of("Z")))
                            val r = Regex("^[A-Z]+\\[[0-9]{12,}\\.?[0-9]{0,4}[+-][0-9]{4}]\$")
                            if (!r.matches(rawValue)) {
                                warnings.add(
                                    "Timestamp for $hl7Field - ${element.name} should provide more " +
                                        "precision. Should be formatted as YYYYMMDDHHMM[SS[.S[S[S[S]+/-ZZZZ"
                                )
                            }
                        }
                        Element.Type.DATE -> {
                            valueString = DateTimeFormatter.ofPattern(Element.datePattern)
                                .format(OffsetDateTime.ofInstant(dtm, ZoneId.of("Z")))
                            // Note that some schema fields of type date could be derived from HL7 date time fields
                            val r = Regex("^[A-Z]+\\[[0-9]{8,}.*")
                            if (!r.matches(rawValue)) {
                                warnings.add(
                                    "Date for $hl7Field - ${element.name} should provide more " +
                                        "precision. Should be formatted as YYYYMMDD"
                                )
                            }
                        }
                        else -> throw IllegalStateException("${element.type} not supported by decodeHl7DateTime")
                    }
                }
            }
        }
        return valueString
    }

    /**
     * Gets the HAPI Terser spec from the provided [hl7Field] string.
     * @returns the HAPI Terser spec
     */
    internal fun getTerserSpec(hl7Field: String): String {
        return if (hl7Field.isNotBlank() && hl7Field.startsWith("MSH")) {
            "/$hl7Field"
        } else {
            "/.$hl7Field"
        }
    }

    companion object {
        /** the length to truncate HD values to. Defaults to 20 */
        const val HD_TRUNCATION_LIMIT = 20
        const val HL7_SPEC_VERSION: String = "2.5.1"
        const val MESSAGE_CODE = "ORU"
        const val MESSAGE_TRIGGER_EVENT = "R01"
        const val SOFTWARE_VENDOR_ORGANIZATION: String = "Centers for Disease Control and Prevention"
        const val SOFTWARE_PRODUCT_NAME: String = "PRIME ReportStream"
        const val NCES_EXTENSION = "_NCES_"
        const val OBX_18_EQUIPMENT_UID_OID: String = "2.16.840.1.113883.3.3719"
        /** the default org name type code. defaults to "L" */
        const val DEFAULT_ORGANIZATION_NAME_TYPE_CODE: String = "L"

        /*
        From the HL7 2.5.1 Ch 2A spec...

        The Hierarchical Designator identifies an entity that has responsibility for managing or
        assigning a defined set of instance identifiers.

        The HD is designed to be used either as a local identifier (with only the <namespace ID> valued)
        or a publicly-assigned identifier, a UID (<universal ID> and <universal ID type> both valued)
         */

        /**
         * List of fields that have the local HD type.
         */
        val HD_FIELDS_LOCAL = listOf(
            "MSH-4-1", "OBR-3-2", "OBR-2-2", "ORC-3-2", "ORC-2-2", "ORC-4-2",
            "PID-3-4-1", "PID-3-6-1", "SPM-2-1-2", "SPM-2-2-2"
        )

        /**
         * List of fields that have the universal HD type
         */
        val HD_FIELDS_UNIVERSAL = listOf(
            "MSH-4-2", "OBR-3-3", "OBR-2-3", "ORC-3-3", "ORC-2-3", "ORC-4-3",
            "PID-3-4-2", "PID-3-6-2", "SPM-2-1-3", "SPM-2-2-3"
        )

        /**
         * List of fields that have a CE type. Note: this is only really used in places
         * where we need to put a CLIA marker in the field as well and there are a
         * lot of CE fields that are *NOT* CLIA fields, so use this correctly.
         */
        val CE_FIELDS = listOf("OBX-15-1")

        // Component specific sub-component length from HL7 specification Chapter 2A
        private val CWE_MAX_LENGTHS = arrayOf(20, 199, 20, 20, 199, 20, 10, 10, 199)
        private val EI_MAX_LENGTHS = arrayOf(199, 20, 199, 6)
        private val EIP_MAX_LENGTHS = arrayOf(427, 427)
        private val HD_MAX_LENGTHS = arrayOf(20, 199, 6)
        private val XTN_MAX_LENGTHS = arrayOf(199, 3, 8, 199, 3, 5, 9, 5, 199, 4, 6, 199)
        private val XAD_MAX_LENGTHS = arrayOf(184, 120, 50, 50, 12, 3, 3, 50, 20, 20, 1, 53, 26, 26)
        private val XCN_MAX_LENGTHS =
            arrayOf(15, 194, 30, 30, 20, 20, 5, 4, 227, 1, 1, 3, 5, 227, 1, 483, 53, 1, 26, 26, 199, 705, 705)
        private val XON_MAX_LENGTHS = arrayOf(50, 20, 4, 1, 3, 227, 5, 227, 1, 20)
        private val XPN_MAX_LENGTHS = arrayOf(194, 30, 30, 20, 20, 6, 1, 1, 483, 53, 1, 26, 26, 199)

        /**
         * Component length table for composite HL7 types taken from HL7 specification Chapter 2A.
         */
        val HL7_COMPONENT_MAX_LENGTH = mapOf(
            "CWE" to CWE_MAX_LENGTHS,
            "EI" to EI_MAX_LENGTHS,
            "EIP" to EIP_MAX_LENGTHS,
            "HD" to HD_MAX_LENGTHS,
            "XAD" to XAD_MAX_LENGTHS,
            "XCN" to XCN_MAX_LENGTHS,
            "XON" to XON_MAX_LENGTHS,
            "XPN" to XPN_MAX_LENGTHS,
            "XTN" to XTN_MAX_LENGTHS,
            // Extend further here
        )

        /**
         * List of ordering provider id fields
         */
        val ORDERING_PROVIDER_ID_FIELDS = listOf("ORC-12", "OBR-16")

        // Do a lazy init because this table may never be used and it is large
        val ncesLookupTable = lazy {
            LookupTable.read("./metadata/tables/nces_id_2021_6_28.csv")
        }
    }
}

/**
 * Trim and truncate the string to the [maxLength] preserving as much of the non-whitespace as possible
 */
fun String.trimAndTruncate(maxLength: Int?): String {
    val startTrimmed = this.trimStart()
    val truncated = if (maxLength != null && startTrimmed.length > maxLength)
        startTrimmed.take(maxLength)
    else
        startTrimmed
    return truncated.trimEnd()
}