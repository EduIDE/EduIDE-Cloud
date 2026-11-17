package org.eclipse.theia.cloud.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Checks that a rejected bearer token never ends up in the log.
 */
@QuarkusTest
class TokenLoggingTests {

    private static final String HEADER = "eyJhbGciOiJSUzI1NiJ9";
    private static final String PAYLOAD = "eyJzdWIiOiJzZWNyZXQtc3R1ZGVudCJ9";
    private static final String FAKE_TOKEN = HEADER + "." + PAYLOAD + ".c2lnbmF0dXJl";

    @Test
    void invalidBearerToken_isNotLogged() {
        List<String> messages = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage() + " " + (record.getThrown() == null ? "" : record.getThrown()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            given().header("Authorization", "Bearer " + FAKE_TOKEN).when().post("/service/some-app").then()
                    .statusCode(401);
        } finally {
            root.removeHandler(handler);
        }
        assertFalse(messages.stream().anyMatch(m -> m.contains(HEADER) || m.contains(PAYLOAD)), messages.toString());
    }
}
