package gov.cdc.prime.router

import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * An `Receiver` represents the agent that the data hub sends reports to
 * (minus the credentials used by that agent, of course). It contains information about
 * the specific topic and schema that the receiver needs.
 *
 * @param name of the receiver
 * @param organizationName of the receiver
 * @param topic defines the set of schemas that can translate to each other
 * @param translation configuration to translate
 * @param jurisdictionalFilter defines the set of elements and regexs that filter the topic
 * @param deidentify transform
 * @param timing defines how to delay reports to the org. If null, then send immediately
 * @param description of the receiver
 * @param transport that the org wishes to receive
 */
data class Receiver(
    val name: String,
    val organizationName: String,
    val topic: String,
    val translation: TranslatorConfiguration,
    val jurisdictionalFilter: List<String> = emptyList(),
    val deidentify: Boolean = false,
    val timing: Timing? = null,
    val description: String = "",
    val transport: TransportType? = null,
) {
    // Custom constructor
    constructor(
        name: String,
        organizationName: String,
        topic: String,
        schemaName: String,
        format: Report.Format = Report.Format.CSV
    ) : this(name, organizationName, topic, CustomConfiguration(schemaName = schemaName, format = format))

    val fullName: String get() = "$organizationName.$name"
    val schemaName: String get() = translation.buildSchemaName()
    val format: Report.Format get() = translation.buildFormat()

    /**
     * Defines how batching of sending should proceed. Allows flexibility of
     * frequency and transmission time on daily basis, but not complete flexibility.
     *
     * @param operation MERGE will combine all reports in the batch into a single batch
     * @param numberPerDay Number of batches per day must be 1 to 3600
     * @param initialTime The time of the day to first send. Must be format of hh:mm.
     * @param timeZone the time zone of the initial sending
     */
    data class Timing(
        val operation: BatchOperation = BatchOperation.NONE,
        val numberPerDay: Int = 1,
        val initialTime: String = "00:00",
        val timeZone: USTimeZone = USTimeZone.EASTERN,
        val maxReportCount: Int = 100,
    ) {
        /**
         * Calculate the next event time.
         *
         * @param now is the current time
         * @param minDurationInSeconds in the future
         */
        fun nextTime(now: OffsetDateTime = OffsetDateTime.now(), minDurationInSeconds: Int = 10): OffsetDateTime {
            if (minDurationInSeconds < 1) error("MinDuration must be at least 1 second")
            val zoneId = ZoneId.of(timeZone.zoneId)
            val zonedNow = now
                .atZoneSameInstant(zoneId)
                .plusSeconds(minDurationInSeconds.toLong())
                .withNano(0)

            val initialSeconds = LocalTime.parse(initialTime).toSecondOfDay()
            val durationFromInitial = zonedNow.toLocalTime().toSecondOfDay() - initialSeconds
            val period = (24 * 60 * 60) / numberPerDay
            val secondsLeftInPeriod = period - ((durationFromInitial + (24 * 60 * 60)) % period)
            return zonedNow
                .plusSeconds(secondsLeftInPeriod.toLong())
                .toOffsetDateTime()
        }

        fun isValid(): Boolean {
            return numberPerDay in 1..(24 * 60)
        }
    }

    enum class BatchOperation {
        NONE,
        MERGE
    }
}