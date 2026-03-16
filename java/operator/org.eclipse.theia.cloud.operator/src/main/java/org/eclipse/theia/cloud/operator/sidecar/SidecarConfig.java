/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.sidecar;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.SidecarSpec;

/**
 * Immutable configuration for a single sidecar container.
 * Derived from either {@link SidecarSpec} (v1beta11 CRD) or legacy AppDefinition options.
 */
public record SidecarConfig(
    String name,
    String image,
    int containerPort,
    List<String> languages,
    String cpuLimit,
    String memoryLimit,
    String cpuRequest,
    String memoryRequest,
    boolean mountWorkspace
) {

    /** Default port all sidecar containers should use internally. */
    public static final int DEFAULT_PORT = 5000;

    /** Legacy AppDefinition option key for language server image. */
    public static final String OPTION_LS_IMAGE = "langserver-image";

    /**
     * Creates a SidecarConfig from a v1beta11 {@link SidecarSpec}, applying defaults for missing fields.
     */
    public static SidecarConfig fromSpec(SidecarSpec spec) {
        return new SidecarConfig(
            spec.getName(),
            spec.getImage(),
            spec.getPort() > 0 ? spec.getPort() : DEFAULT_PORT,
            spec.getLanguages() != null ? spec.getLanguages() : List.of(),
            spec.getCpuLimit() != null && !spec.getCpuLimit().isBlank() ? spec.getCpuLimit() : "500m",
            spec.getMemoryLimit() != null && !spec.getMemoryLimit().isBlank() ? spec.getMemoryLimit() : "1Gi",
            spec.getCpuRequest() != null && !spec.getCpuRequest().isBlank() ? spec.getCpuRequest() : "100m",
            spec.getMemoryRequest() != null && !spec.getMemoryRequest().isBlank() ? spec.getMemoryRequest() : "256Mi",
            spec.isMountWorkspace()
        );
    }

    /**
     * Creates a SidecarConfig from legacy AppDefinition options map.
     * Used for backward compatibility with v1beta10 AppDefinitions that have
     * {@code options["langserver-image"]} set.
     */
    public static SidecarConfig fromLegacyOptions(Map<String, String> options) {
        return new SidecarConfig(
            "langserver",
            options.get(OPTION_LS_IMAGE),
            DEFAULT_PORT,
            List.of(),
            options.getOrDefault("langserver-cpu-limit", "500m"),
            options.getOrDefault("langserver-memory-limit", "1Gi"),
            options.getOrDefault("langserver-cpu-request", "100m"),
            options.getOrDefault("langserver-memory-request", "256Mi"),
            true
        );
    }

    /**
     * Returns the environment variable name for this sidecar's host.
     * Example: sidecar name "lang-java" → "SIDECAR_LANG_JAVA_HOST"
     */
    public String hostEnvVar() {
        return "SIDECAR_" + name.toUpperCase().replace("-", "_") + "_HOST";
    }

    /**
     * Returns the environment variable name for this sidecar's port.
     * Example: sidecar name "lang-java" → "SIDECAR_LANG_JAVA_PORT"
     */
    public String portEnvVar() {
        return "SIDECAR_" + name.toUpperCase().replace("-", "_") + "_PORT";
    }

    /**
     * Checks if the given AppDefinition has any sidecar configuration
     * (either v1beta11 sidecars list or legacy options).
     */
    public static boolean hasSidecars(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return false;
        }
        // Check v1beta11 sidecars list
        List<SidecarSpec> sidecars = appDef.getSpec().getSidecars();
        if (sidecars != null && !sidecars.isEmpty()) {
            return true;
        }
        // Fallback: check legacy options
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options == null) {
            return false;
        }
        String lsImage = options.get(OPTION_LS_IMAGE);
        return lsImage != null && !lsImage.isBlank();
    }

    /**
     * Extracts the list of SidecarConfigs from an AppDefinition.
     * Checks spec.sidecars first; falls back to legacy options.
     */
    public static List<SidecarConfig> getSidecarConfigs(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return List.of();
        }
        // v1beta11 path
        List<SidecarSpec> sidecars = appDef.getSpec().getSidecars();
        if (sidecars != null && !sidecars.isEmpty()) {
            return sidecars.stream().map(SidecarConfig::fromSpec).toList();
        }
        // Legacy path
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options != null) {
            String lsImage = options.get(OPTION_LS_IMAGE);
            if (lsImage != null && !lsImage.isBlank()) {
                return List.of(fromLegacyOptions(options));
            }
        }
        return List.of();
    }
}
