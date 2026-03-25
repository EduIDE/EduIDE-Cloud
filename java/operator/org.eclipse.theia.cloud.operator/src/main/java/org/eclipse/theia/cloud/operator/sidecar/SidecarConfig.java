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

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.SidecarSpec;

/**
 * Immutable configuration for a single sidecar container.
 * Derived from {@link SidecarSpec} (v1beta11 CRD).
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
     * (v1beta11 sidecars list).
     */
    public static boolean hasSidecars(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return false;
        }

        return appDef.getSpec().hasSidecars();
    }

    /**
     * Checks if the given AppDefinition requires a shared workspace PVC.
     */
    public static boolean requiresSharedWorkspace(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return false;
        }

        return appDef.getSpec().requiresSharedWorkspace();
    }

    /**
     * Extracts the list of SidecarConfigs from an AppDefinition.
     * Uses spec.sidecars only.
     */
    public static List<SidecarConfig> getSidecarConfigs(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return List.of();
        }

        List<SidecarSpec> sidecars = appDef.getSpec().getSidecars();
        if (sidecars != null && !sidecars.isEmpty()) {
            return sidecars.stream().map(SidecarConfig::fromSpec).toList();
        }

        return List.of();
    }
}
