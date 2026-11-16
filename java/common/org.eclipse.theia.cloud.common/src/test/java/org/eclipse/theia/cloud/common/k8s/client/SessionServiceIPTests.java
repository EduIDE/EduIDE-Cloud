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
package org.eclipse.theia.cloud.common.k8s.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;

class SessionServiceIPTests {

    private static Service service(String name, String ip, boolean sidecar) {
        ServiceBuilder builder = new ServiceBuilder().withNewMetadata().withName(name)
                .withOwnerReferences(new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder().withName("session")
                        .withKind("Session").build())
                .endMetadata().withNewSpec().withClusterIP(ip).endSpec();
        if (sidecar) {
            builder.editMetadata().addToLabels(TheiaCloudClient.SIDECAR_LABEL, "lsp").endMetadata();
        }
        return builder.build();
    }

    @Test
    void ideServiceIsChosenWhenASidecarServiceComesFirst() {
        List<Service> services = List.of(service("session-lsp", "10.0.0.9", true),
                service("session-ide", "10.0.0.1", false), service("session-ide-int", "10.0.0.2", false));

        assertEquals("10.0.0.1", TheiaCloudClient.selectSessionServiceIP(services, "session", false).orElseThrow());
        assertEquals("10.0.0.2", TheiaCloudClient.selectSessionServiceIP(services, "session", true).orElseThrow());
    }

    @Test
    void aSessionWithOnlySidecarServicesHasNoIdeService() {
        assertTrue(TheiaCloudClient
                .selectSessionServiceIP(List.of(service("session-lsp", "10.0.0.9", true)), "session", false).isEmpty());
    }

    @Test
    void servicesOfOtherSessionsAreIgnored() {
        assertTrue(TheiaCloudClient
                .selectSessionServiceIP(List.of(service("x", "10.0.0.1", false)), "other", false).isEmpty());
    }
}
