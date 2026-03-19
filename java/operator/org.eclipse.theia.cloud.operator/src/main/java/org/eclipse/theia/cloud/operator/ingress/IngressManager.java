package org.eclipse.theia.cloud.operator.ingress;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.util.K8sUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPBackendRef;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPBackendRefBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPHeader;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPHeaderBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPHeaderFilterBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPPathMatch;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPPathMatchBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPPathModifierBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRequestRedirectFilterBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteFilter;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteFilterBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteMatch;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteMatchBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteRule;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteRuleBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteSpec;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteSpecBuilder;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPURLRewriteFilterBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * Centralized manager for HTTPRoute operations (Gateway API).
 *
 * Caller intent:
 * - find the shared route template for an app definition
 * - expose a session path via a dedicated session-owned HTTPRoute
 */
@Singleton
public class IngressManager {

    private static final Logger LOGGER = LogManager.getLogger(IngressManager.class);
    /**
     * Envoy Gateway runtime expression for the current request path.
     * This value is interpreted by Envoy; other Gateway API implementations may
     * treat it as a plain literal string.
     */
    private static final String ENVOY_REQUEST_PATH_EXPRESSION = "%REQ(:PATH)%";
    private static final int HTTP_CONFLICT = 409;
    private static final String SESSION_ROUTE_SUFFIX = "-route";

    @Inject
    private TheiaCloudClient client;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    @Inject
    private IngressPathProvider pathProvider;

    /**
     * Gets the shared app-level HTTPRoute for an app definition.
     *
     * This route is treated as static configuration managed outside the operator:
     * it provides the parentRefs and hostnames that every session-specific
     * HTTPRoute should inherit.
     */
    public Optional<HTTPRoute> getIngress(AppDefinition appDefinition, String correlationId) {
        Optional<HTTPRoute> route = K8sUtil.getExistingHttpRoute(
                client.kubernetes(),
                client.namespace(),
                appDefinition.getMetadata().getName(),
                appDefinition.getMetadata().getUid());
        if (route.isEmpty()) {
            LOGGER.debug(formatLogMessage(correlationId,
                    "No HTTPRoute found for app definition " + appDefinition.getMetadata().getName()));
        }
        return route;
    }

    /**
     * Exposes an eager session through a dedicated session-owned HTTPRoute.
     *
     * @return the full URL for the session
     */
    public String addRuleForSession(
            HTTPRoute routeTemplate,
            Service service,
            AppDefinition appDefinition,
            Session session,
            int instance,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, instance);
        return createOrUpdateSessionRoute(routeTemplate, service, appDefinition, session, path, correlationId);
    }

    /**
     * Exposes a lazy session through a dedicated session-owned HTTPRoute.
     *
     * @return the full URL for the session
     */
    public String addRuleForSession(
            HTTPRoute routeTemplate,
            Service service,
            AppDefinition appDefinition,
            Session session,
            String correlationId) {

        String path = pathProvider.getPath(appDefinition, session);
        return createOrUpdateSessionRoute(routeTemplate, service, appDefinition, session, path, correlationId);
    }

    private String createOrUpdateSessionRoute(
            HTTPRoute routeTemplate,
            Service service,
            AppDefinition appDefinition,
            Session session,
            String path,
            String correlationId) {

        String routeName = buildSessionRouteName(session);
        String serviceName = service.getMetadata().getName();
        int port = appDefinition.getSpec().getPort();
        HTTPRoute desiredRoute = buildSessionRoute(routeTemplate, serviceName, port, path, session, appDefinition);

        try {
            client.httpRoutes().operation().resource(desiredRoute).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != HTTP_CONFLICT) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Failed to create session HTTPRoute " + routeName + " for path " + path), e);
                throw e;
            }

            // Retries for the same Session should converge on one stable route name.
            client.httpRoutes().edit(correlationId, routeName, existingRoute -> {
                if (existingRoute == null) {
                    throw new KubernetesClientException("HTTPRoute " + routeName + " not found");
                }
                existingRoute.setSpec(desiredRoute.getSpec());
                existingRoute.getMetadata().setLabels(desiredRoute.getMetadata().getLabels());
                existingRoute.getMetadata().setOwnerReferences(desiredRoute.getMetadata().getOwnerReferences());
            });
        }

        LOGGER.info(formatLogMessage(correlationId,
                "Configured session HTTPRoute " + routeName + " for path " + path
                        + " and backend service " + serviceName));
        return arguments.getInstancesHost() + ensureTrailingSlash(path);
    }

    private HTTPRoute buildSessionRoute(HTTPRoute routeTemplate, String serviceName, int port, String path,
            Session session, AppDefinition appDefinition) {
        HTTPRouteSpec templateSpec = getOrCreateSpec(routeTemplate);
        List<String> hosts = buildRouteHosts(appDefinition, templateSpec.getHostnames());

        return new HTTPRouteBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        // Use the Session UID so retries and reconciliations keep targeting the same route while
                        // staying well within Kubernetes object name limits.
                        .withName(buildSessionRouteName(session))
                        .withNamespace(client.namespace())
                        .withLabels(LabelsUtil.createSessionLabels(session, appDefinition))
                        .withOwnerReferences(buildSessionOwnerReference(session))
                        .build())
                .withSpec(new HTTPRouteSpecBuilder()
                        // Keep gateway attachment and hostname configuration in the shared app-level route so Helm
                        // remains the single source of truth for ingress topology.
                        .withParentRefs(templateSpec.getParentRefs())
                        .withHostnames(hosts)
                        .withRules(createRedirectRule(path), createRouteRule(serviceName, port, path))
                        .build())
                .build();
    }

    private List<String> buildRouteHosts(AppDefinition appDefinition, List<String> templateHostnames) {
        if (templateHostnames != null && !templateHostnames.isEmpty()) {
            return new ArrayList<>(templateHostnames);
        }

        String instancesHost = arguments.getInstancesHost();
        List<String> hosts = new ArrayList<>();
        hosts.add(instancesHost);

        List<String> prefixes = appDefinition.getSpec().getIngressHostnamePrefixes();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                hosts.add(prefix + instancesHost);
            }
        }

        return hosts;
    }

    private OwnerReference buildSessionOwnerReference(Session session) {
        return new OwnerReferenceBuilder()
                .withApiVersion(Session.API)
                .withKind(Session.KIND)
                .withName(session.getMetadata().getName())
                .withUid(session.getMetadata().getUid())
                .build();
    }

    private String buildSessionRouteName(Session session) {
        return session.getMetadata().getUid() + SESSION_ROUTE_SUFFIX;
    }

    private HTTPRouteSpec getOrCreateSpec(HTTPRoute route) {
        HTTPRouteSpec spec = route.getSpec();
        if (spec == null) {
            spec = new HTTPRouteSpecBuilder().build();
            route.setSpec(spec);
        }
        return spec;
    }

    /**
     * Creates the backend routing rule for a session path.
     *
     * The X-Forwarded-Uri header uses an Envoy Gateway runtime expression and
     * therefore requires Envoy Gateway for correct behavior.
     */
    private HTTPRouteRule createRouteRule(String serviceName, int port, String path) {
        HTTPRouteMatch pathPrefixMatch = new HTTPRouteMatchBuilder()
                .withPath(new HTTPPathMatchBuilder()
                        .withType("PathPrefix")
                        .withValue(ensureTrailingSlash(path))
                        .build())
                .build();

        HTTPHeader forwardedUriHeader = new HTTPHeaderBuilder()
                .withName("X-Forwarded-Uri")
                .withValue(ENVOY_REQUEST_PATH_EXPRESSION)
                .build();

        HTTPRouteFilter requestHeaderFilter = new HTTPRouteFilterBuilder()
                .withType("RequestHeaderModifier")
                .withRequestHeaderModifier(new HTTPHeaderFilterBuilder()
                        .withSet(forwardedUriHeader)
                        .build())
                .build();

        HTTPRouteFilter urlRewriteFilter = new HTTPRouteFilterBuilder()
                .withType("URLRewrite")
                .withUrlRewrite(new HTTPURLRewriteFilterBuilder()
                        .withPath(new HTTPPathModifierBuilder()
                                .withType("ReplacePrefixMatch")
                                .withReplacePrefixMatch("/")
                                .build())
                        .build())
                .build();

        HTTPBackendRef backendRef = new HTTPBackendRefBuilder()
                .withName(serviceName)
                .withPort(port)
                .build();

        return new HTTPRouteRuleBuilder()
                .withMatches(pathPrefixMatch)
                .withFilters(requestHeaderFilter, urlRewriteFilter)
                .withBackendRefs(backendRef)
                .build();
    }

    private HTTPRouteRule createRedirectRule(String path) {
        HTTPRouteMatch exactMatch = new HTTPRouteMatchBuilder()
                .withPath(new HTTPPathMatchBuilder()
                        .withType("Exact")
                        .withValue(path)
                        .build())
                .build();

        HTTPRouteFilter redirectFilter = new HTTPRouteFilterBuilder()
                .withType("RequestRedirect")
                .withRequestRedirect(new HTTPRequestRedirectFilterBuilder()
                        .withStatusCode(302)
                        .withPath(new HTTPPathModifierBuilder()
                                .withType("ReplaceFullPath")
                                .withReplaceFullPath(ensureTrailingSlash(path))
                                .build())
                        .build())
                .build();

        return new HTTPRouteRuleBuilder()
                .withMatches(exactMatch)
                .withFilters(redirectFilter)
                .build();
    }

    private String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}
