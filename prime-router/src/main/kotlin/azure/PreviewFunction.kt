package gov.cdc.prime.router.azure

import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import com.microsoft.azure.functions.HttpMethod
import com.microsoft.azure.functions.HttpRequestMessage
import com.microsoft.azure.functions.HttpResponseMessage
import com.microsoft.azure.functions.annotation.AuthorizationLevel
import com.microsoft.azure.functions.annotation.FunctionName
import com.microsoft.azure.functions.annotation.HttpTrigger
import gov.cdc.prime.router.Receiver
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.Sender
import gov.cdc.prime.router.messages.PreviewMessage
import gov.cdc.prime.router.tokens.OktaAuthentication
import org.apache.logging.log4j.kotlin.Logging

class PreviewFunction(
    private val oktaAuthentication: OktaAuthentication = OktaAuthentication(PrincipalLevel.SYSTEM_ADMIN),
    private val workflowEngine: WorkflowEngine
) : Logging {
    /**
     * The preview end-point does a translation of the input message to the output payload
     */
    @FunctionName("preview")
    fun preview(
        @HttpTrigger(
            name = "preview",
            methods = [HttpMethod.POST],
            authLevel = AuthorizationLevel.ANONYMOUS,
            route = "preview"
        ) request: HttpRequestMessage<String?>
    ): HttpResponseMessage {
        return oktaAuthentication.checkAccess(request) {
            try {
                val parameters = checkRequest(request)
                val body = processRequest(parameters)
                HttpUtilities.okResponse(request, body)
            } catch (ex: IllegalArgumentException) {
                HttpUtilities.badRequestResponse(request, ex.message ?: "")
            } catch (ex: Exception) {
                logger.error("Internal error for ${it.userName} request: $ex")
                HttpUtilities.internalErrorResponse(request)
            }
        }
    }

    private fun badRequest(message: String): Nothing {
        throw IllegalArgumentException(message)
    }

    data class FunctionParameters(
        val previewMessage: PreviewMessage,
        val receiver: Receiver,
        val sender: Sender,
    )

    /**
     * Look at the request body.
     * Check for valid values with defaults are assumed if not specified.
     */
    internal fun checkRequest(request: HttpRequestMessage<String?>): FunctionParameters {
        val body = request.body
            ?: badRequest("Missing body")
        val previewMessage = mapper.readValue(body, PreviewMessage::class.java)
        val receiver = previewMessage.receiver
            ?: workflowEngine.settings.findReceiver(previewMessage.receiverName)
            ?: badRequest("Missing receiver")
        val sender = previewMessage.sender
            ?: workflowEngine.settings.findSender(previewMessage.senderName)
            ?: badRequest("Missing sender")
        return FunctionParameters(previewMessage, receiver, sender)
    }

    /**
     * Main logic of the Azure function. Useful for unit testing.
     */
    internal fun processRequest(parameters: FunctionParameters): String {
        return readReport(parameters)
            .filter(parameters)
            .serialize(parameters)
    }

    private fun readReport(parameters: FunctionParameters): Report {
        TODO("$parameters")
    }

    private fun Report.filter(parameters: FunctionParameters): Report {
        TODO("$parameters, $this")
    }

    private fun Report.serialize(parameters: FunctionParameters): String {
        TODO("$parameters, $this")
    }

    companion object {
        private val mapper = jacksonMapperBuilder().build()

        private fun mapBodyFormatToSenderFormat(bodyFormat: String): Sender.Format {
            return when (bodyFormat) {
                "CSV", "CSV_SINGLE", "INTERNAL" -> Sender.Format.CSV
                "HL7", "HL7_BATCH" -> Sender.Format.HL7
                else -> error("Unknown body format type: $bodyFormat")
            }
        }
    }
}