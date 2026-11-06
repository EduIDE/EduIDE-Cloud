package org.eclipse.theia.cloud.operator.databridge;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.DataBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.DataInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.sentry.ISpan;
import io.sentry.SpanStatus;
import org.eclipse.theia.cloud.common.tracing.TraceContext;
import org.eclipse.theia.cloud.common.tracing.Tracing;

/**
 * Handles asynchronous data injection into sessions via the data bridge. Polls
 * the health endpoint until the data
 * bridge is ready, then injects data once.
 */
@Singleton
public class AsyncDataInjector {

    private static final Logger LOGGER = LogManager.getLogger(AsyncDataInjector.class);

    /**
     * The data bridge runs in the plugin host, which only starts once a browser connects, so this has to outlast however
     * long a user takes to open the claimed session.
     */
    private static final int MAX_HEALTH_CHECKS = 900;

    /** Consumers read the environment for 10 seconds after activation, so this has to stay well below that. */
    private static final int HEALTH_CHECK_INTERVAL_MS = 2000;

    /** A POST that keeps failing is a bad payload or a broken bridge, not a slow browser. */
    private static final int MAX_INJECTION_FAILURES = 5;

    /** A prewarmed pod starts before its session exists, so its monitor can only learn the secret from here. */
    static final String SESSION_SECRET_ENV = "THEIACLOUD_SESSION_SECRET";

    private final ScheduledExecutorService scheduler;

    @Inject
    private DataBridgeClient dataBridgeClient;

    @Inject
    private TheiaCloudClient client;

    public AsyncDataInjector() {
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    /**
     * Schedules asynchronous data injection for a session. Polls the health
     * endpoint until ready, then injects data
     * once.
     * 
     * @param parentSpan    The parent span to create a child span from for tracing
     * @param session       The session to inject data into
     * @param envVars       The environment variables to inject
     * @param correlationId For logging/tracing
     */
    public void scheduleInjection(ISpan parentSpan, Session session, Map<String, String> envVars,
            String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDef = session.getSpec().getAppDefinition();
        Map<String, String> payload = new HashMap<>(envVars);
        if (session.getSpec().getSessionSecret() != null) {
            payload.put(SESSION_SECRET_ENV, session.getSpec().getSessionSecret());
        }
        int envVarCount = payload.size();

        LOGGER.info(formatLogMessage(correlationId, "Scheduling async data injection for session: " + sessionName));

        // Extract trace context BEFORE scheduling - the parent span will be finished by
        // the time scheduler runs
        Optional<TraceContext> traceContext = TraceContext.fromSpan(parentSpan);

        scheduler.submit(() -> {
            // Create a new transaction linked to the same trace (parent span is already
            // finished)
            ISpan span = Tracing.continueTraceAsync(traceContext, "databridge.inject", "Data bridge injection");
            span.setTag("session.name", sessionName);
            span.setTag("app_definition", appDef);
            span.setData("correlation_id", correlationId);
            span.setData("env_var_count", envVarCount);

            pollHealthThenInject(span, session, payload, correlationId, 0, 0);
        });
    }

    /**
     * Polls the health endpoint and injects data when ready. Recursively schedules
     * itself for retries. Aggregate metrics (health check attempts, outcome) are tracked
     * on the parent span rather than creating individual spans for each health check.
     * 
     * @param span          The parent span from scheduleInjection - aggregate metrics are tracked here
     * @param session       The session to inject data into
     * @param envVars       The environment variables to inject
     * @param correlationId For logging/tracing
     * @param attempt       The current attempt number (0-indexed)
     * @param failures      How many injection POSTs have failed so far
     */
    private void pollHealthThenInject(ISpan span, Session session, Map<String, String> envVars,
            String correlationId, int attempt, int failures) {
        String sessionName = session.getSpec().getName();

        if (attempt >= MAX_HEALTH_CHECKS) {
            LOGGER.error(formatLogMessage(correlationId, "Data bridge not ready after "
                    + MAX_HEALTH_CHECKS * HEALTH_CHECK_INTERVAL_MS / 1000 + "s for session: " + sessionName));

            span.setTag("outcome", "timeout");
            span.setData("health_check_attempts", attempt);
            Tracing.finish(span, SpanStatus.DEADLINE_EXCEEDED);
            return;
        }

        // The pool instance goes back to the pool when the session ends and can be claimed by someone else, so
        // injecting for a session that is gone would hand this user's credentials to the next one.
        Optional<Session> liveSession;
        try {
            liveSession = client.sessions().get(sessionName);
        } catch (RuntimeException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not read session " + sessionName + ", retrying"), e);
            scheduleNext(span, session, envVars, correlationId, attempt, failures);
            return;
        }
        if (liveSession.isEmpty()
                || !session.getMetadata().getUid().equals(liveSession.get().getMetadata().getUid())) {
            LOGGER.info(formatLogMessage(correlationId,
                    "Session " + sessionName + " ended before its data could be injected"));
            span.setTag("outcome", "session_gone");
            span.setData("health_check_attempts", attempt);
            Tracing.finish(span, SpanStatus.CANCELLED);
            return;
        }

        // Check if data bridge is healthy (no individual span, just aggregate metrics)
        boolean healthy = dataBridgeClient.healthCheck(sessionName, correlationId);

        if (healthy) {
            // Ready - inject data once
            LOGGER.info(formatLogMessage(correlationId,
                    "Data bridge ready for session: " + sessionName + " (attempt " + (attempt + 1) + ")"));

            // Track aggregate metrics on the span
            span.setData("health_check_attempts", attempt + 1);
            span.setData("time_to_ready_ms", (attempt + 1) * HEALTH_CHECK_INTERVAL_MS);

            ISpan injectSpan = Tracing.childSpan(span, "databridge.inject_data", "Inject environment data");
            injectSpan.setData("env_var_count", envVars.size());

            String failureReason;
            try {
                Optional<DataInjectionResponse> response = dataBridgeClient.injectData(session, envVars, correlationId);

                if (response.isPresent() && response.get().isSuccess()) {
                    LOGGER.info(
                            formatLogMessage(correlationId, "Data injected successfully for session: " + sessionName));
                    injectSpan.setTag("outcome", "success");
                    Tracing.finishSuccess(injectSpan);
                    span.setTag("outcome", "success");
                    Tracing.finishSuccess(span);
                    return;
                }
                failureReason = response.isEmpty() ? "no_response" : "api_error";
                LOGGER.error(formatLogMessage(correlationId, "Data injection failed for session: " + sessionName + " - "
                        + response.map(DataInjectionResponse::getError).orElse("no response")));
                injectSpan.setTag("outcome", failureReason);
                Tracing.finish(injectSpan, SpanStatus.INTERNAL_ERROR);
            } catch (Exception e) {
                failureReason = "exception";
                LOGGER.error(formatLogMessage(correlationId, "Exception during data injection"), e);
                injectSpan.setTag("outcome", "exception");
                Tracing.finishError(injectSpan, e);
            }

            if (failures + 1 >= MAX_INJECTION_FAILURES) {
                span.setTag("outcome", "injection_failed");
                span.setTag("failure.reason", failureReason);
                Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                return;
            }
            scheduleNext(span, session, envVars, correlationId, attempt, failures + 1);
            return;
        }

        // Not ready - schedule next health check
        if (attempt == 0) {
            LOGGER.debug(formatLogMessage(correlationId,
                    "Data bridge not ready yet for session: " + sessionName + ", polling..."));
        }

        scheduleNext(span, session, envVars, correlationId, attempt, failures);
    }

    private void scheduleNext(ISpan span, Session session, Map<String, String> envVars, String correlationId,
            int attempt, int failures) {
        scheduler.schedule(() -> pollHealthThenInject(span, session, envVars, correlationId, attempt + 1, failures),
                HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the scheduler. Should be called when the operator is shutting
     * down.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
