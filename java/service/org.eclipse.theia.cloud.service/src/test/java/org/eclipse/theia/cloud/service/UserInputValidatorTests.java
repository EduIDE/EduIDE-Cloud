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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserInputValidatorTests {

    @Test
    void acceptsRealIdentifiers() {
        assertTrue(UserInputValidator.isValid("3034464@stud.th-mannheim.de"));
        assertTrue(UserInputValidator.isValid("s.steger@th-mannheim.de"));
        assertTrue(UserInputValidator.isValid("test3@linhuber.org"));
        assertTrue(UserInputValidator.isValid("nojelseseli@gmail.com"));
        assertTrue(UserInputValidator.isValid("user+tag@example.com"));
        assertTrue(UserInputValidator.isValid("plainuser"));
    }

    @Test
    void rejectsTheObservedAttackPayloads() {
        assertFalse(UserInputValidator.isValid("../../etc"));
        assertFalse(UserInputValidator.isValid("../../../x"));
        assertFalse(UserInputValidator.isValid("x\r\nX-Injected: 1"));
        assertFalse(UserInputValidator.isValid("<script>alert(1)</script>"));
        assertFalse(UserInputValidator.isValid("anon-inj$(id)"));
        assertFalse(UserInputValidator.isValid("anon-inj`id`"));
        assertFalse(UserInputValidator.isValid("anon-inj2;id"));
        assertFalse(UserInputValidator.isValid("a/b"));
    }

    @Test
    void rejectsNullBlankAndOverlong() {
        assertFalse(UserInputValidator.isValid(null));
        assertFalse(UserInputValidator.isValid(""));
        assertFalse(UserInputValidator.isValid(" "));
        assertFalse(UserInputValidator.isValid("a".repeat(UserInputValidator.MAX_USER_LENGTH + 1)));
    }
}
