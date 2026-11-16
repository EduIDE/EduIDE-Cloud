package org.eclipse.theia.cloud.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Checks the HTTP edge of the service: only configured origins pass CORS and the operational endpoints are not served
 * on the public port.
 */
@QuarkusTest
@TestProfile(HttpEdgeTests.CorsProfile.class)
class HttpEdgeTests {

    private static final String ALLOWED_ORIGIN = "https://landing.example.test";

    public static class CorsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.cors", "true", "quarkus.http.cors.origins", ALLOWED_ORIGIN);
        }
    }

    @Test
    void preflight_configuredOrigin_isAllowed() {
        given().header("Origin", ALLOWED_ORIGIN).header("Access-Control-Request-Method", "POST").when()
                .options("/service/some-app").then()
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN));
    }

    @Test
    void preflight_otherOrigin_isNotAllowed() {
        given().header("Origin", "https://other.example.test").header("Access-Control-Request-Method", "POST").when()
                .options("/service/some-app").then().header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void operationalEndpoints_notServedOnPublicPort() {
        given().when().get("/q/metrics").then().statusCode(404);
        given().when().get("/q/openapi").then().statusCode(404);
    }

    @Test
    void operationalEndpoints_servedOnManagementPort() {
        given().port(9001).when().get("/q/metrics").then().statusCode(200);
    }
}
