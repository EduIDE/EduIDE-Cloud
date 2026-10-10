/********************************************************************************
 * Copyright (C) 2026 TUM and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the operator's allowlist policy for custom environment variables requested through a
 * {@code Session} (originating from an unauthenticated launch request in anonymous deployments).
 * <p>
 * A launch request may ask for arbitrary Secrets/ConfigMaps to be mounted into the session pod via
 * {@code envFrom} and for arbitrary variables via {@code env.fromMap}. Without a policy this lets
 * any caller exfiltrate namespace Secrets or override platform variables. This filter is the
 * authoritative choke point: it is applied in the operator (which actually mounts the values), so
 * it protects both the lazy and eager code paths and even hand-crafted {@code Session} resources.
 * <p>
 * Policy:
 * <ul>
 * <li>Secret/ConfigMap {@code envFrom} references are kept only if their name is on the configured
 * allowlist. The default allowlist is empty, i.e. nothing may be injected.</li>
 * <li>{@code fromMap} variables are dropped entirely unless custom map variables are enabled, and
 * reserved platform variables (see {@link #RESERVED_PREFIXES} and {@link #RESERVED_NAMES}) are
 * always dropped so a caller cannot override or forge them.</li>
 * </ul>
 */
public class CustomEnvFilter {

    /** Env var name prefixes the platform owns; a request must never set these. */
    public static final Set<String> RESERVED_PREFIXES = Set.of("THEIACLOUD_", "KEYCLOAK_");

    /** Individual env var names that are dangerous to let a caller control. */
    public static final Set<String> RESERVED_NAMES = Set.of("LD_PRELOAD", "LD_LIBRARY_PATH", "NODE_OPTIONS");

    private final Set<String> allowedSecrets;
    private final Set<String> allowedConfigMaps;
    private final boolean allowCustomEnvFromMap;

    public CustomEnvFilter(Collection<String> allowedSecrets, Collection<String> allowedConfigMaps,
            boolean allowCustomEnvFromMap) {
        this.allowedSecrets = normalize(allowedSecrets);
        this.allowedConfigMaps = normalize(allowedConfigMaps);
        this.allowCustomEnvFromMap = allowCustomEnvFromMap;
    }

    private static Set<String> normalize(Collection<String> names) {
        if (names == null) {
            return Set.of();
        }
        return names.stream().filter(name -> name != null && !name.isBlank()).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * @return the requested Secret names that are on the allowlist, preserving order.
     */
    public List<String> allowedSecretRefs(List<String> requested) {
        return filterRefs(requested, allowedSecrets);
    }

    /**
     * @return the requested ConfigMap names that are on the allowlist, preserving order.
     */
    public List<String> allowedConfigMapRefs(List<String> requested) {
        return filterRefs(requested, allowedConfigMaps);
    }

    /**
     * @return the requested Secret names that are NOT allowed (for logging).
     */
    public List<String> rejectedSecretRefs(List<String> requested) {
        return rejected(requested, allowedSecrets);
    }

    /**
     * @return the requested ConfigMap names that are NOT allowed (for logging).
     */
    public List<String> rejectedConfigMapRefs(List<String> requested) {
        return rejected(requested, allowedConfigMaps);
    }

    private static List<String> filterRefs(List<String> requested, Set<String> allowed) {
        if (requested == null) {
            return List.of();
        }
        return requested.stream().filter(name -> name != null && allowed.contains(name.trim()))
                .collect(Collectors.toList());
    }

    private static List<String> rejected(List<String> requested, Set<String> allowed) {
        if (requested == null) {
            return List.of();
        }
        return requested.stream().filter(name -> name == null || !allowed.contains(name.trim()))
                .collect(Collectors.toList());
    }

    /**
     * Filters the {@code fromMap} variables: drops everything when custom map variables are
     * disabled, and always drops reserved platform variables. Preserves insertion order.
     */
    public Map<String, String> allowedMapEnv(Map<String, String> requested) {
        if (requested == null || requested.isEmpty() || !allowCustomEnvFromMap) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            if (!isReserved(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * @return the keys of {@code fromMap} that were dropped because they are reserved or disabled.
     */
    public List<String> rejectedMapKeys(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (!allowCustomEnvFromMap) {
            return requested.keySet().stream().collect(Collectors.toList());
        }
        return requested.keySet().stream().filter(CustomEnvFilter::isReserved).collect(Collectors.toList());
    }

    public static boolean isReserved(String key) {
        if (key == null) {
            return true;
        }
        String upper = key.trim().toUpperCase();
        if (RESERVED_NAMES.contains(upper)) {
            return true;
        }
        return RESERVED_PREFIXES.stream().anyMatch(upper::startsWith);
    }
}
