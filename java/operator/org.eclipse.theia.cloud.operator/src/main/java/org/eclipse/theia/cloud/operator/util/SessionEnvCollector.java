package org.eclipse.theia.cloud.operator.util;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.sentry.Sentry;

/**
 * Collects environment variables for a session from various sources: direct env vars, ConfigMaps, and Secrets.
 */
@Singleton
public class SessionEnvCollector {

    private static final Logger LOGGER = LogManager.getLogger(SessionEnvCollector.class);

    @Inject
    private TheiaCloudClient client;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    private CustomEnvFilter envFilter() {
        return new CustomEnvFilter(arguments.getAllowedEnvFromSecrets(), arguments.getAllowedEnvFromConfigMaps(),
                arguments.isAllowCustomEnvFromMap());
    }

    /**
     * Collects all custom environment variables for a session.
     * 
     * @param session       The session to collect env vars for
     * @param correlationId For logging/tracing
     * @return Map of environment variable names to values
     */
    public Map<String, String> collect(Session session, String correlationId) {
        Map<String, String> result = new HashMap<>();

        // Apply the operator's allowlist policy first. The session's env requests may originate
        // from an unauthenticated launch request, so unlisted Secrets/ConfigMaps and reserved
        // platform variables must never be resolved and pushed into the pod.
        CustomEnvFilter filter = envFilter();
        logRejected(session, filter, correlationId);

        // Collect direct env vars
        Map<String, String> directEnvVars = filter.allowedMapEnv(session.getSpec().getEnvVars());
        if (directEnvVars != null && !directEnvVars.isEmpty()) {
            result.putAll(directEnvVars);
            LOGGER.debug(formatLogMessage(correlationId, "Collected " + directEnvVars.size() + " direct env vars"));
        }

        // Resolve env vars from ConfigMaps
        List<String> configMapNames = filter.allowedConfigMapRefs(session.getSpec().getEnvVarsFromConfigMaps());
        if (configMapNames != null) {
            for (String configMapName : configMapNames) {
                resolveConfigMap(configMapName, result, correlationId);
            }
        }

        // Resolve env vars from Secrets
        List<String> secretNames = filter.allowedSecretRefs(session.getSpec().getEnvVarsFromSecrets());
        if (secretNames != null) {
            for (String secretName : secretNames) {
                resolveSecret(secretName, result, correlationId);
            }
        }

        LOGGER.info(formatLogMessage(correlationId,
                "Collected " + result.size() + " total env vars for session " + session.getSpec().getName()));

        return result;
    }

    private void resolveConfigMap(String configMapName, Map<String, String> result, String correlationId) {
        try {
            ConfigMap configMap = client.kubernetes().configMaps().inNamespace(client.namespace())
                    .withName(configMapName).get();

            if (configMap == null) {
                LOGGER.warn(formatLogMessage(correlationId, "ConfigMap not found: " + configMapName));
                return;
            }

            Map<String, String> data = configMap.getData();
            if (data != null && !data.isEmpty()) {
                result.putAll(data);
                LOGGER.debug(formatLogMessage(correlationId,
                        "Resolved " + data.size() + " env vars from ConfigMap: " + configMapName));
            }
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to resolve ConfigMap: " + configMapName), e);
        }
    }

    private void resolveSecret(String secretName, Map<String, String> result, String correlationId) {
        try {
            Secret secret = client.kubernetes().secrets().inNamespace(client.namespace()).withName(secretName).get();

            if (secret == null) {
                LOGGER.warn(formatLogMessage(correlationId, "Secret not found: " + secretName));
                return;
            }

            Map<String, String> data = secret.getData();
            if (data != null && !data.isEmpty()) {
                // Secret data is base64 encoded
                int successCount = 0;
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    try {
                        String decodedValue = new String(Base64.getDecoder().decode(entry.getValue()),
                                StandardCharsets.UTF_8);
                        result.put(entry.getKey(), decodedValue);
                        successCount++;
                    } catch (IllegalArgumentException e) {
                        Sentry.captureException(e);
                        LOGGER.warn(formatLogMessage(correlationId, "Failed to decode Base64 value for key '"
                                + entry.getKey() + "' in Secret: " + secretName), e);
                    }
                }
                LOGGER.debug(formatLogMessage(correlationId,
                        "Resolved " + successCount + " env vars from Secret: " + secretName));
            }
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to resolve Secret: " + secretName), e);
        }
    }

    private void logRejected(Session session, CustomEnvFilter filter, String correlationId) {
        List<String> rejectedSecrets = filter.rejectedSecretRefs(session.getSpec().getEnvVarsFromSecrets());
        List<String> rejectedConfigMaps = filter.rejectedConfigMapRefs(session.getSpec().getEnvVarsFromConfigMaps());
        List<String> rejectedMapKeys = filter.rejectedMapKeys(session.getSpec().getEnvVars());
        if (!rejectedSecrets.isEmpty() || !rejectedConfigMaps.isEmpty() || !rejectedMapKeys.isEmpty()) {
            LOGGER.warn(formatLogMessage(correlationId,
                    "Dropped env injection not permitted by the operator allowlist for session "
                            + session.getSpec().getName() + ". Secrets=" + rejectedSecrets + " ConfigMaps="
                            + rejectedConfigMaps + " fromMap keys=" + rejectedMapKeys));
        }
    }
}
