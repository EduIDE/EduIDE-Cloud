/********************************************************************************
 * Copyright (C) 2024 EclipseSource and others.
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
package org.eclipse.theia.cloud.service.appdefinition;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What every logged-in user may learn about an AppDefinition. The full spec (images, resource limits, options) stays
 * with the admin API.
 */
@Schema(name = "AppDefinitionSummary", description = "Public information about an app definition.")
public class AppDefinitionSummary {

    @Schema(description = "The name of the app definition.")
    public String name;

    public AppDefinitionSummary() {
    }

    public AppDefinitionSummary(String name) {
        this.name = name;
    }
}
