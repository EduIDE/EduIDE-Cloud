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
package org.eclipse.theia.cloud.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class LabelsUtilTests {

    // The Kubernetes label value syntax: empty, or a <=63 char string that starts and ends with an
    // alphanumeric and only contains alphanumerics, '-', '_' and '.' in between.
    private static final Pattern VALID_LABEL_VALUE = Pattern
            .compile("^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$");

    @Test
    void shortAlphanumericValueIsUnchanged() {
        assertEquals("3034464_at_stud_th_mannheim_de",
                LabelsUtil.truncateLabelValue("3034464_at_stud_th_mannheim_de"));
    }

    @Test
    void truncationThatLandsOnSeparatorIsTrimmed() {
        // 62 'a' + '_' is exactly 63 chars, so no length truncation happens, but the trailing
        // separator would still be an invalid label-value endpoint and must be removed.
        String value = "a".repeat(62) + "_";
        String result = LabelsUtil.truncateLabelValue(value);
        assertEquals("a".repeat(62), result);
        assertTrue(VALID_LABEL_VALUE.matcher(result).matches());
    }

    @Test
    void overlongValueIsTruncatedAndEndpointTrimmed() {
        // Mimics a sanitized user id whose 63-char cut ends on a separator.
        String value = "a".repeat(62) + "_at_b";
        String result = LabelsUtil.truncateLabelValue(value);
        assertEquals("a".repeat(62), result);
        assertTrue(VALID_LABEL_VALUE.matcher(result).matches());
    }

    @Test
    void valueThatSanitizesToOnlySeparatorsBecomesEmpty() {
        // A degenerate input such as "." sanitizes to "_"; trimming leaves an (allowed) empty value
        // rather than an API-server-rejected "_".
        String result = LabelsUtil.truncateLabelValue("_");
        assertEquals("", result);
        assertTrue(VALID_LABEL_VALUE.matcher(result).matches());
    }
}
