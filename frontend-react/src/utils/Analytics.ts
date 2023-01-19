import { getAppInsights } from "../TelemetryService";

export enum EventName {
    TABLE_FILTER = "Table Filter",
    TABLE_PAGINATION = "Table Pagination",
}

export const trackAppInsightEvent = (eventName: string, eventData: any) => {
    const appInsights = getAppInsights();
    const telemetryEvent = {
        name: eventName,
        properties: eventData,
    };

    if (eventName !== "") appInsights?.trackEvent(telemetryEvent);
};
