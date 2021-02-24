package gov.cdc.prime.router.azure

import com.fasterxml.jackson.core.JsonFactory
import com.google.common.net.HttpHeaders
import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.HttpMethod
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import com.microsoft.azure.functions.HttpStatus
import com.microsoft.azure.functions.annotation.AuthorizationLevel
import com.microsoft.azure.functions.annotation.FunctionName
import com.microsoft.azure.functions.annotation.HttpTrigger
import com.microsoft.azure.functions.annotation.StorageAccount
import gov.cdc.prime.router.ClientSource
import gov.cdc.prime.router.OrganizationClient
import gov.cdc.prime.router.OrganizationService
import gov.cdc.prime.router.REPORT_MAX_BYTES
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ResultDetail
import gov.cdc.prime.router.azure.db.enums.TaskAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.NumberFormatException
import java.time.OffsetDateTime
import java.util.logging.Level

/**
 * Azure Functions with HTTP Trigger.
 * This is basically the "front end" of the Hub. Reports come in here.
 */
class ReportFunction {
    private val clientParameter = "client"
    private val optionParameter = "option"
    private val defaultParameter = "default"
    private val defaultSeparator = ":"
    private val jsonMediaType = "application/json" // TODO: find a good media library

    enum class Options {
        None,
        ValidatePayload,
        CheckConnections,
        SkipSend,
        SkipInvalidItems,
    }

    data class ValidatedRequest(
        val options: Options,
        val defaults: Map<String, String>,
        val errors: List<ResultDetail>,
        val warnings: List<ResultDetail>,
        val report: Report?,
        val httpStatus: HttpStatus
    )

    /**
     * @see docs/openapi.yml
     */
    @FunctionName("reports")
    @StorageAccount("AzureWebJobsStorage")
    fun run(
        @HttpTrigger(
            name = "req",
            methods = [HttpMethod.POST],
            authLevel = AuthorizationLevel.FUNCTION
        ) request: HttpRequestMessage<String?>,
        context: ExecutionContext,
    ): HttpResponseMessage {
        try {
            val workflowEngine = WorkflowEngine()
            val actionHistory = ActionHistory(TaskAction.receive, context)
            actionHistory.trackActionParams(request)
            val validatedRequest = validateRequest(workflowEngine, request)
            val httpResponseMessage = when {
                validatedRequest.options == Options.CheckConnections -> {
                    workflowEngine.checkConnections()
                    okResponse(request, validatedRequest)
                }
                validatedRequest.report == null -> {
                    notOKResponse(request, validatedRequest)
                }
                validatedRequest.options == Options.ValidatePayload -> {
                    okResponse(request, validatedRequest)
                }
                else -> {
                    context.logger.info("Successfully reported: ${validatedRequest.report.id}.")
                    routeReport(context, workflowEngine, validatedRequest, actionHistory)
                    val responseBody = createResponseBody(validatedRequest, actionHistory)
                    workflowEngine.receiveReport(validatedRequest, actionHistory)
                    createdResponse(request, responseBody)
                }
            }
            actionHistory.trackActionResult(httpResponseMessage)
            workflowEngine.recordAction(actionHistory)
            actionHistory.queueMessages() // Must be done after creating db records.
            return httpResponseMessage
        } catch (e: Exception) {
            context.logger.log(Level.SEVERE, e.message, e)
            return internalErrorResponse(request)
        }
    }

    private fun validateRequest(engine: WorkflowEngine, request: HttpRequestMessage<String?>): ValidatedRequest {
        val errors = mutableListOf<ResultDetail>()
        val warnings = mutableListOf<ResultDetail>()

        val (sizeStatus, errMsg) = payloadSizeCheck(request)
        if (sizeStatus != HttpStatus.OK) {
            errors.add(ResultDetail.report(errMsg))
            // If size is too big, we ignore the option.
            return ValidatedRequest(Options.None, emptyMap(), errors, warnings, null, sizeStatus)
        }

        val optionsText = request.queryParameters.getOrDefault(optionParameter, "")
        val options = if (optionsText.isNotBlank()) {
            try {
                Options.valueOf(optionsText)
            } catch (e: IllegalArgumentException) {
                errors.add(ResultDetail.param(optionParameter, "'$optionsText' is not valid"))
                Options.None
            }
        } else {
            Options.None
        }

        if (options == Options.CheckConnections) {
            return ValidatedRequest(options, emptyMap(), errors, warnings, null, HttpStatus.OK)
        }

        val clientName = request.headers[clientParameter] ?: request.queryParameters.getOrDefault(clientParameter, "")
        if (clientName.isBlank())
            errors.add(ResultDetail.param(clientParameter, "Expected a '$clientParameter' query parameter"))
        val client = engine.metadata.findClient(clientName)
        if (client == null)
            errors.add(ResultDetail.param(clientParameter, "'$clientName' is not a valid"))
        val schema = engine.metadata.findSchema(client?.schema ?: "")

        val contentType = request.headers.getOrDefault(HttpHeaders.CONTENT_TYPE.toLowerCase(), "")
        if (contentType.isBlank()) {
            errors.add(ResultDetail.param(HttpHeaders.CONTENT_TYPE, "missing"))
        } else if (client != null && client.format.mimeType != contentType) {
            errors.add(ResultDetail.param(HttpHeaders.CONTENT_TYPE, "expecting '${client.format.mimeType}'"))
        }

        val content = request.body ?: ""
        if (content.isEmpty()) {
            errors.add(ResultDetail.param("Content", "expecting a post message with content"))
        }

        if (client == null || schema == null || content.isEmpty() || errors.isNotEmpty()) {
            return ValidatedRequest(options, emptyMap(), errors, warnings, null, HttpStatus.BAD_REQUEST)
        }

        val defaultValues = if (request.queryParameters.containsKey(defaultParameter)) {
            val values = request.queryParameters.getOrDefault(defaultParameter, "").split(",")
            values.mapNotNull {
                val parts = it.split(defaultSeparator)
                if (parts.size != 2) {
                    errors.add(ResultDetail.report("'$it' is not a valid default"))
                    return@mapNotNull null
                }
                val element = schema.findElement(parts[0])
                if (element == null) {
                    errors.add(ResultDetail.report("'${parts[0]}' is not a valid element name"))
                    return@mapNotNull null
                }
                val error = element.checkForError(parts[1])
                if (error != null) {
                    errors.add(ResultDetail.param(defaultParameter, error))
                    return@mapNotNull null
                }
                Pair(parts[0], parts[1])
            }.toMap()
        } else {
            emptyMap()
        }

        if (content.isEmpty() || errors.isNotEmpty()) {
            return ValidatedRequest(options, defaultValues, errors, warnings, null, HttpStatus.BAD_REQUEST)
        }

        var report = createReport(engine, client, content, defaultValues, errors, warnings)
        if (options != Options.SkipInvalidItems && errors.isNotEmpty()) {
            report = null
        }
        return ValidatedRequest(options, defaultValues, errors, warnings, report, HttpStatus.OK)
    }

    private fun createReport(
        engine: WorkflowEngine,
        client: OrganizationClient,
        content: String,
        defaults: Map<String, String>,
        errors: MutableList<ResultDetail>,
        warnings: MutableList<ResultDetail>
    ): Report? {
        return when (client.format) {
            OrganizationClient.Format.CSV -> {
                try {
                    val readResult = engine.csvSerializer.readExternal(
                        schemaName = client.schema,
                        input = ByteArrayInputStream(content.toByteArray()),
                        sources = listOf(ClientSource(organization = client.organization.name, client = client.name)),
                        defaultValues = defaults
                    )
                    errors += readResult.errors
                    warnings += readResult.warnings
                    readResult.report
                } catch (e: Exception) {
                    errors.add(ResultDetail.report(e.message ?: ""))
                    null
                }
            }
        }
    }

    private fun routeReport(
        context: ExecutionContext,
        workflowEngine: WorkflowEngine,
        validatedRequest: ValidatedRequest,
        actionHistory: ActionHistory,
    ) {
        if (validatedRequest.options == Options.ValidatePayload ||
            validatedRequest.options == Options.CheckConnections
        ) return
        workflowEngine.db.transact { txn ->
            workflowEngine
                .translator
                .filterAndTranslateByService(validatedRequest.report!!, validatedRequest.defaults)
                .forEach { (report, service) ->
                    sendToDestination(
                        report, service, context, workflowEngine,
                        validatedRequest, actionHistory, txn
                    )
                }
        }
    }

    private fun sendToDestination(
        report: Report,
        service: OrganizationService,
        context: ExecutionContext,
        workflowEngine: WorkflowEngine,
        validatedRequest: ValidatedRequest,
        actionHistory: ActionHistory,
        txn: DataAccessTransaction
    ) {
        val loggerMsg: String
        when {
            validatedRequest.options == Options.SkipSend -> {
                val event = ReportEvent(Event.EventAction.NONE, report.id)
                workflowEngine.dispatchReport(event, report, actionHistory, service, txn)
                loggerMsg = "Queue: ${event.toQueueMessage()}"
            }
            service.batch != null -> {
                val time = service.batch.nextBatchTime()
                // Always force a batched report to be saved in our INTERNAL format
                val batchReport = report.copy(bodyFormat = Report.Format.INTERNAL)
                val event = ReceiverEvent(Event.EventAction.BATCH, service.fullName, time)
                workflowEngine.dispatchReport(event, batchReport, actionHistory, service, txn)
                loggerMsg = "Queue: ${event.toQueueMessage()}"
            }
            service.format == Report.Format.HL7 -> {
                report
                    .split()
                    .forEach {
                        val event = ReportEvent(Event.EventAction.SEND, it.id)
                        workflowEngine.dispatchReport(event, it, actionHistory, service, txn)
                    }
                loggerMsg = "Queue: ${report.itemCount} reports"
            }
            else -> {
                val event = ReportEvent(Event.EventAction.SEND, report.id)
                workflowEngine.dispatchReport(event, report, actionHistory, service, txn)
                loggerMsg = "Queue: ${event.toQueueMessage()}"
            }
        }
        context.logger.info(loggerMsg)
    }

    // todo I think all of this info is now in ActionHistory.  Move to there.   Already did destinations.
    private fun createResponseBody(
        result: ValidatedRequest,
        actionHistory: ActionHistory? = null,
    ): String {
        val factory = JsonFactory()
        val outStream = ByteArrayOutputStream()
        factory.createGenerator(outStream).use {
            it.useDefaultPrettyPrinter()
            it.writeStartObject()
            if (result.report != null) {
                it.writeStringField("id", result.report.id.toString())
                it.writeNumberField("reportItemCount", result.report.itemCount)
            } else
                it.writeNullField("id")
            actionHistory?.prettyPrintDestinationsJson(it, WorkflowEngine.metadata)

            it.writeNumberField("warningCount", result.warnings.size)
            it.writeNumberField("errorCount", result.errors.size)

            fun writeDetailsArray(field: String, array: List<ResultDetail>) {
                it.writeArrayFieldStart(field)
                array.forEach { error ->
                    it.writeStartObject()
                    it.writeStringField("scope", error.scope.toString())
                    it.writeStringField("id", error.id)
                    it.writeStringField("details", error.details)
                    it.writeEndObject()
                }
                it.writeEndArray()
            }
            writeDetailsArray("errors", result.errors)
            writeDetailsArray("warnings", result.warnings)
            it.writeEndObject()
        }
        return outStream.toString()
    }

    private fun okResponse(
        request: HttpRequestMessage<String?>,
        validatedRequest: ValidatedRequest
    ): HttpResponseMessage {
        return request
            .createResponseBuilder(HttpStatus.OK)
            .body(createResponseBody(validatedRequest))
            .header(HttpHeaders.CONTENT_TYPE, jsonMediaType)
            .build()
    }

    private fun notOKResponse(
        request: HttpRequestMessage<String?>,
        validatedRequest: ValidatedRequest,
    ): HttpResponseMessage {
        return request
            .createResponseBuilder(validatedRequest.httpStatus)
            .body(createResponseBody(validatedRequest))
            .header(HttpHeaders.CONTENT_TYPE, jsonMediaType)
            .build()
    }

    private fun createdResponse(
        request: HttpRequestMessage<String?>,
        responseBody: String,
    ): HttpResponseMessage {
        return request
            .createResponseBuilder(HttpStatus.CREATED)
            .body(responseBody)
            .header(HttpHeaders.CONTENT_TYPE, jsonMediaType)
            .build()
    }

    private fun internalErrorResponse(
        request: HttpRequestMessage<String?>
    ): HttpResponseMessage {
        return request
            .createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
            .body("Internal error at ${OffsetDateTime.now()}")
            .build()
    }

    private fun payloadSizeCheck(request: HttpRequestMessage<String?>): Pair<HttpStatus, String> {
        val contentLengthStr = request.headers["content-length"]
        if (contentLengthStr == null) {
            return HttpStatus.LENGTH_REQUIRED to "ERROR: No content-length header found.  Refusing this request."
        }
        val contentLength = try {
            contentLengthStr.toLong()
        } catch (e: NumberFormatException) {
            return HttpStatus.LENGTH_REQUIRED to "ERROR: content-length header is not a number"
        }
        when {
            contentLength < 0 -> {
                return HttpStatus.LENGTH_REQUIRED to "ERROR: negative content-length $contentLength"
            }
            contentLength > REPORT_MAX_BYTES -> {
                return HttpStatus.PAYLOAD_TOO_LARGE to
                    "ERROR: content-length $contentLength is larger than max $REPORT_MAX_BYTES"
            }
        }
        // content-length header is ok.  Now check size of actual body as well
        val content = request.body
        if (content != null && content.length > REPORT_MAX_BYTES) {
            return HttpStatus.PAYLOAD_TOO_LARGE to
                "ERROR: body size ${content.length} is larger than max $REPORT_MAX_BYTES " +
                "(content-length header = $contentLength"
        }
        return HttpStatus.OK to ""
    }
}