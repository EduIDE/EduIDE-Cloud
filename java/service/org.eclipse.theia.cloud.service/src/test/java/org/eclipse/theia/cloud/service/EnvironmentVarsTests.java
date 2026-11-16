package org.eclipse.theia.cloud.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EnvironmentVarsTests {

    @Test
    void toString_listsNamesOfFromMapButNotValues() {
        EnvironmentVars env = new EnvironmentVars();
        env.fromMap = Map.of("API_KEY", "value-that-must-not-be-logged");

        String text = env.toString();

        assertTrue(text.contains("API_KEY"));
        assertFalse(text.contains("value-that-must-not-be-logged"));
    }

    @Test
    void launchRequestToString_doesNotContainFromMapValues() {
        LaunchRequest request = new LaunchRequest();
        request.env.fromMap = Map.of("API_KEY", "value-that-must-not-be-logged");

        assertFalse(request.toString().contains("value-that-must-not-be-logged"));
    }
}
