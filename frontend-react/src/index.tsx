import { createRoot } from "react-dom/client";
import { CacheProvider } from "rest-hooks";
// need to include browser here for now so that app can have easy access to history
import { BrowserRouter as Router } from "react-router-dom";

import App from "./App";
// compiled css so the resources process for the static site by the compiler
import "./content/generated/global.out.css";
import { ai, withInsights } from "./TelemetryService";

// Initialize the App Insights connection and React app plugin from Microsoft
// The plugin is provided in the AppInsightsProvider in AppWrapper.tsx
ai.initialize();
withInsights(console);

const root = createRoot(document.getElementById("root")!);
root.render(
    <CacheProvider>
        <Router>
            <App />
        </Router>
    </CacheProvider>
);
