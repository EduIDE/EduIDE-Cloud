package org.eclipse.theia.cloud.common.k8s.resource.session;

import java.util.List;

/**
 * Response object for data injection operations.
 */
public class DataInjectionResponse {

    private final boolean success;
    private final String message;
    private final String error;
    private final List<String> ignoredKeys;

    /**
     * Creates a data injection response.
     * 
     * @param success Whether the injection was successful
     * @param message Success message (null if failed)
     * @param error   Error message (null if successful)
     */
    public DataInjectionResponse(boolean success, String message, String error) {
        this(success, message, error, List.of());
    }

    public DataInjectionResponse(boolean success, String message, String error, List<String> ignoredKeys) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.ignoredKeys = ignoredKeys == null ? List.of() : List.copyOf(ignoredKeys);
    }

    /**
     * Creates a successful response for an injection in which the data bridge ignored some keys.
     * 
     * @param message     Success message
     * @param ignoredKeys Names (never values) of the keys the data bridge did not store
     * @return Response indicating success
     */
    public static DataInjectionResponse success(String message, List<String> ignoredKeys) {
        return new DataInjectionResponse(true, message, null, ignoredKeys);
    }

    /**
     * Creates a successful response.
     * 
     * @param message Success message
     * @return Response indicating success
     */
    public static DataInjectionResponse success(String message) {
        return new DataInjectionResponse(true, message, null);
    }

    /**
     * Creates a failure response.
     * 
     * @param error Error message
     * @return Response indicating failure
     */
    public static DataInjectionResponse failure(String error) {
        return new DataInjectionResponse(false, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    /** Names of the keys the data bridge ignored because it does not know them. */
    public List<String> getIgnoredKeys() {
        return ignoredKeys;
    }

    @Override
    public String toString() {
        return "DataInjectionResponse{" + "success=" + success + ", message='" + message + '\'' + ", error='"
                + error + '\'' + '}';
    }
}

