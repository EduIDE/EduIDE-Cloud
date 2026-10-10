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
package org.eclipse.theia.cloud.service;

import java.util.regex.Pattern;

/**
 * Validates the caller-supplied {@code user} identifier used in anonymous (Keycloak-disabled)
 * deployments. There the value is fully attacker-controlled and flows into Kubernetes resource
 * names, labels, the Session/Workspace CR spec and - via the operator's YAML templates - into pod
 * manifests and logs. Restricting it to a conservative character set closes CRLF/log injection,
 * path traversal, YAML injection and shell-metacharacter payloads at the service boundary.
 * <p>
 * The allowed set covers real identifiers in use (matriculation numbers and e-mail addresses such
 * as {@code 3034464@stud.th-mannheim.de} or {@code s.steger@th-mannheim.de}) while rejecting
 * whitespace, control characters and the metacharacters needed for the above attacks.
 */
public final class UserInputValidator {

    public static final int MAX_USER_LENGTH = 128;

    private static final Pattern VALID_USER = Pattern.compile("^[A-Za-z0-9._@+-]{1,128}$");

    private UserInputValidator() {
    }

    /**
     * @return {@code true} if the given user identifier is safe to use.
     */
    public static boolean isValid(String user) {
        return user != null && VALID_USER.matcher(user).matches();
    }
}
