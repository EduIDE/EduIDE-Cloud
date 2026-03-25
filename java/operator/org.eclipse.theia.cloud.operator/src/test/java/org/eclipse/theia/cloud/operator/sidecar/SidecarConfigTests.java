/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.SidecarSpec;
import org.junit.jupiter.api.Test;

class SidecarConfigTests {

    // ========== fromSpec ==========

    @Test
    void fromSpec_allFieldsPresent() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 9090, List.of("java", "kotlin"),
                "1", "2Gi", "200m", "512Mi", true);

        SidecarConfig config = SidecarConfig.fromSpec(spec);

        assertEquals("langserver", config.name());
        assertEquals("img:latest", config.image());
        assertEquals(9090, config.containerPort());
        assertEquals(List.of("java", "kotlin"), config.languages());
        assertEquals("1", config.cpuLimit());
        assertEquals("2Gi", config.memoryLimit());
        assertEquals("200m", config.cpuRequest());
        assertEquals("512Mi", config.memoryRequest());
        assertTrue(config.mountWorkspace());
    }

    @Test
    void fromSpec_defaultPort() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 0, List.of(), null, null, null, null, false);

        SidecarConfig config = SidecarConfig.fromSpec(spec);

        assertEquals(SidecarConfig.DEFAULT_PORT, config.containerPort());
    }

    @Test
    void fromSpec_nullLanguages() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, null, null, null, null, null, false);

        SidecarConfig config = SidecarConfig.fromSpec(spec);

        assertEquals(List.of(), config.languages());
    }

    @Test
    void fromSpec_defaultResourceLimits() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, List.of(), null, null, null, null, false);

        SidecarConfig config = SidecarConfig.fromSpec(spec);

        assertEquals("500m", config.cpuLimit());
        assertEquals("1Gi", config.memoryLimit());
        assertEquals("100m", config.cpuRequest());
        assertEquals("256Mi", config.memoryRequest());
    }

    @Test
    void fromSpec_blankResourceLimitsGetDefaults() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, List.of(), "  ", "  ", "  ", "  ", false);

        SidecarConfig config = SidecarConfig.fromSpec(spec);

        assertEquals("500m", config.cpuLimit());
        assertEquals("1Gi", config.memoryLimit());
        assertEquals("100m", config.cpuRequest());
        assertEquals("256Mi", config.memoryRequest());
    }

    @Test
    void fromSpec_mountWorkspaceDefaultsToFalse() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, List.of(), null, null, null, null, null);

        SidecarConfig config = SidecarConfig.fromSpec(spec);

        assertFalse(config.mountWorkspace());
    }

    // ========== Env var naming ==========

    @Test
    void hostEnvVar_simpleName() {
        SidecarConfig config = createConfig("langserver");
        assertEquals("SIDECAR_LANGSERVER_HOST", config.hostEnvVar());
    }

    @Test
    void hostEnvVar_hyphenatedName() {
        SidecarConfig config = createConfig("rust-analyzer");
        assertEquals("SIDECAR_RUST_ANALYZER_HOST", config.hostEnvVar());
    }

    @Test
    void portEnvVar_simpleName() {
        SidecarConfig config = createConfig("langserver");
        assertEquals("SIDECAR_LANGSERVER_PORT", config.portEnvVar());
    }

    @Test
    void portEnvVar_hyphenatedName() {
        SidecarConfig config = createConfig("rust-analyzer");
        assertEquals("SIDECAR_RUST_ANALYZER_PORT", config.portEnvVar());
    }

    // ========== hasSidecars ==========

    @Test
    void hasSidecars_nullAppDef() {
        assertFalse(SidecarConfig.hasSidecars(null));
    }

    @Test
    void hasSidecars_nullSpec() {
        AppDefinition appDef = new AppDefinition();
        assertFalse(SidecarConfig.hasSidecars(appDef));
    }

    @Test
    void hasSidecars_emptySidecarsList() {
        AppDefinition appDef = createAppDefWithSidecars(List.of());
        assertFalse(SidecarConfig.hasSidecars(appDef));
    }

    @Test
    void hasSidecars_withSidecars() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, List.of("java"), null, null, null, null, true);
        AppDefinition appDef = createAppDefWithSidecars(List.of(spec));
        assertTrue(SidecarConfig.hasSidecars(appDef));
    }

    @Test
    void requiresSharedWorkspace_falseWhenNoSidecarMountsWorkspace() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, List.of("java"), null, null, null, null, false);
        AppDefinition appDef = createAppDefWithSidecars(List.of(spec));

        assertFalse(SidecarConfig.requiresSharedWorkspace(appDef));
    }

    @Test
    void requiresSharedWorkspace_trueWhenSidecarMountsWorkspace() {
        SidecarSpec spec = createSpec("langserver", "img:latest", 5000, List.of("java"), null, null, null, null, true);
        AppDefinition appDef = createAppDefWithSidecars(List.of(spec));

        assertTrue(SidecarConfig.requiresSharedWorkspace(appDef));
    }

    // ========== getSidecarConfigs ==========

    @Test
    void getSidecarConfigs_fromSpec() {
        SidecarSpec spec1 = createSpec("java-ls", "img1:latest", 5000, List.of("java"), null, null, null, null, true);
        SidecarSpec spec2 = createSpec("rust-ls", "img2:latest", 5001, List.of("rust"), null, null, null, null, false);
        AppDefinition appDef = createAppDefWithSidecars(List.of(spec1, spec2));

        List<SidecarConfig> configs = SidecarConfig.getSidecarConfigs(appDef);

        assertEquals(2, configs.size());
        assertEquals("java-ls", configs.get(0).name());
        assertEquals("rust-ls", configs.get(1).name());
    }

    @Test
    void getSidecarConfigs_nullAppDef() {
        assertEquals(List.of(), SidecarConfig.getSidecarConfigs(null));
    }

    @Test
    void getSidecarConfigs_noSidecarsNoLegacy() {
        AppDefinition appDef = createAppDefWithSidecars(List.of());
        assertEquals(List.of(), SidecarConfig.getSidecarConfigs(appDef));
    }

    // ========== Helpers ==========

    private SidecarConfig createConfig(String name) {
        return new SidecarConfig(name, "img:latest", 5000, List.of(), "500m", "1Gi", "100m", "256Mi", false);
    }

    private SidecarSpec createSpec(String name, String image, int port, List<String> languages,
            String cpuLimit, String memoryLimit, String cpuRequest, String memoryRequest, Boolean mountWorkspace) {
        SidecarSpec spec = new SidecarSpec();
        setFieldUnchecked(spec, "name", name);
        setFieldUnchecked(spec, "image", image);
        setFieldUnchecked(spec, "port", port);
        setFieldUnchecked(spec, "languages", languages);
        setFieldUnchecked(spec, "cpuLimit", cpuLimit);
        setFieldUnchecked(spec, "memoryLimit", memoryLimit);
        setFieldUnchecked(spec, "cpuRequest", cpuRequest);
        setFieldUnchecked(spec, "memoryRequest", memoryRequest);
        setFieldUnchecked(spec, "mountWorkspace", mountWorkspace);
        return spec;
    }

    private void setFieldUnchecked(Object obj, String fieldName, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    private AppDefinition createAppDefWithSidecars(List<SidecarSpec> sidecars) {
        AppDefinition appDef = new AppDefinition();
        AppDefinitionSpec spec = new AppDefinitionSpec();
        setFieldUnchecked(spec, "sidecars", sidecars);
        appDef.setSpec(spec);
        return appDef;
    }

}
