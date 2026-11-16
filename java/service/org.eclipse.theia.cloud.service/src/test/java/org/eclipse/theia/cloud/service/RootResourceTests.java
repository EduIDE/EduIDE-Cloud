/********************************************************************************
 * Copyright (C) 2023 EclipseSource and others.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceSpec;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.WorkspaceStatus;
import org.eclipse.theia.cloud.common.util.TheiaCloudError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

/**
 * Unit tests for {@link RootResource}. Disable authorization via {@link TestSecurity} annotation as this is a unit test
 * of the resource itself. Thus, we do not want authentication interceptors to trigger when calling the resource's
 * methods.
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
class RootResourceTests {

    private static final String APP_ID = "asdfghjkl";
    private static final String TEST_USER = "TestUser";
    private static final String OTHER_TEST_USER = "OtherTestUser";
    private static final String APP_DEFINITION = "test-app-definition";
    private static final String TEST_WORKSPACE = "test-workspace";
    private static final String SESSION_URL = "https://session.test";

    @InjectMock
    ApplicationProperties applicationProperties;

    @InjectMock
    K8sUtil k8sUtil;

    @InjectMock
    TheiaCloudUser user;

    @Inject
    RootResource fixture;

    @BeforeEach
    void mockApplicationProperties() {
        Mockito.when(applicationProperties.isUseKeycloak()).thenReturn(true);
    }

    @Test
    void launch_otherUser_throwForbidden() {
        // Prepare
        mockUser(false, TEST_USER);
        LaunchRequest launchRequest = new LaunchRequest();
        launchRequest.appId = APP_ID;
        launchRequest.user = OTHER_TEST_USER;

        // Execute
        TheiaCloudWebException exception = assertThrows(TheiaCloudWebException.class, () -> {
            fixture.launch(launchRequest);

        });

        // Assert
        assertEquals(Status.FORBIDDEN.getStatusCode(), exception.getResponse().getStatus());
    }

    @Test
    void launch_workspaceOfOtherUser_throwForbiddenAndKeepWorkspace() {
        mockUser(false, OTHER_TEST_USER);
        mockAppDefinition();
        mockWorkspaceNameTakenBy(TEST_USER, new WorkspaceStatus());

        assertForbidden(() -> fixture.launch(launchRequest(OTHER_TEST_USER)));
        verifyNoSessionAndNoDeletion();
    }

    @Test
    void launch_workspaceOfOtherUserWhenSessionStartFails_throwForbiddenAndKeepWorkspace() {
        // Without the owner check the failing launch runs into the cleanup, which deletes the workspace.
        mockUser(false, OTHER_TEST_USER);
        mockAppDefinition();
        mockWorkspaceNameTakenBy(TEST_USER, new WorkspaceStatus());
        Mockito.when(k8sUtil.launchWorkspaceSession(anyString(), any(), anyInt(), any()))
                .thenThrow(new TheiaCloudWebException(TheiaCloudError.SESSION_LAUNCH_TIMEOUT));

        assertForbidden(() -> fixture.launch(launchRequest(OTHER_TEST_USER)));
        verifyNoSessionAndNoDeletion();
    }

    @Test
    void launch_erroneousWorkspaceOfOtherUser_throwForbiddenAndKeepWorkspace() {
        mockUser(false, OTHER_TEST_USER);
        mockAppDefinition();
        WorkspaceStatus status = new WorkspaceStatus();
        status.setError(TheiaCloudError.WORKSPACE_LAUNCH_TIMEOUT);
        mockWorkspaceNameTakenBy(TEST_USER, status);

        assertForbidden(() -> fixture.launch(launchRequest(OTHER_TEST_USER)));
        verifyNoSessionAndNoDeletion();
    }

    @Test
    void launch_ownExistingWorkspace_launchesSession() {
        mockUser(false, TEST_USER);
        mockAppDefinition();
        Mockito.when(k8sUtil.getWorkspace(TEST_USER, TEST_WORKSPACE))
                .thenReturn(Optional.of(workspace(TEST_USER, new WorkspaceStatus())));
        mockSessionStart();

        assertEquals(SESSION_URL, fixture.launch(launchRequest(TEST_USER)));
        verifyWorkspaceSessionStartedFor(TEST_USER);
    }

    @Test
    void launch_newWorkspace_launchesSession() {
        mockUser(false, TEST_USER);
        mockAppDefinition();
        mockWorkspaceNameTakenBy(TEST_USER, new WorkspaceStatus());
        mockSessionStart();

        assertEquals(SESSION_URL, fixture.launch(launchRequest(TEST_USER)));
        verifyWorkspaceSessionStartedFor(TEST_USER);
    }

    @Test
    void launch_newWorkspaceWhenSessionStartFails_deletesWorkspace() {
        mockUser(false, TEST_USER);
        mockAppDefinition();
        mockWorkspaceNameTakenBy(TEST_USER, new WorkspaceStatus());
        Mockito.when(k8sUtil.launchWorkspaceSession(anyString(), any(), anyInt(), any()))
                .thenThrow(new TheiaCloudWebException(TheiaCloudError.SESSION_LAUNCH_TIMEOUT));

        assertThrows(TheiaCloudWebException.class, () -> fixture.launch(launchRequest(TEST_USER)));
        Mockito.verify(k8sUtil).deleteWorkspace(anyString(), eq(TEST_WORKSPACE));
    }

    @Test
    void launch_newWorkspaceWhenSessionStillStarting_keepsWorkspace() {
        mockUser(false, TEST_USER);
        mockAppDefinition();
        mockWorkspaceNameTakenBy(TEST_USER, new WorkspaceStatus());
        Mockito.when(k8sUtil.launchWorkspaceSession(anyString(), any(), anyInt(), any()))
                .thenThrow(new TheiaCloudWebException(Status.SERVICE_UNAVAILABLE, "Session is still starting."));

        assertThrows(TheiaCloudWebException.class, () -> fixture.launch(launchRequest(TEST_USER)));
        Mockito.verify(k8sUtil, never()).deleteWorkspace(anyString(), anyString());
    }

    @Test
    void launch_anonymousMode_ownerLaunchesAndOtherIsRefused() {
        Mockito.when(applicationProperties.isUseKeycloak()).thenReturn(false);
        mockAppDefinition();
        mockWorkspaceNameTakenBy(TEST_USER, new WorkspaceStatus());
        mockSessionStart();

        assertEquals(SESSION_URL, fixture.launch(launchRequest(TEST_USER)));
        verifyWorkspaceSessionStartedFor(TEST_USER);

        Mockito.clearInvocations(k8sUtil);
        assertForbidden(() -> fixture.launch(launchRequest(OTHER_TEST_USER)));
        verifyNoSessionAndNoDeletion();
    }

    // ---
    // Utility methods
    // ---

    private void mockAppDefinition() {
        Mockito.when(k8sUtil.hasAppDefinition(APP_DEFINITION)).thenReturn(true);
    }

    /** The owner-filtered lookup finds nothing for anyone, and creating returns the existing workspace of the owner. */
    private void mockWorkspaceNameTakenBy(String owner, WorkspaceStatus status) {
        Mockito.when(k8sUtil.getWorkspace(anyString(), anyString())).thenReturn(Optional.empty());
        Mockito.when(k8sUtil.createWorkspace(anyString(), any())).thenReturn(workspace(owner, status));
    }

    private void mockSessionStart() {
        Mockito.when(k8sUtil.launchWorkspaceSession(anyString(), any(), anyInt(), any())).thenReturn(SESSION_URL);
    }

    private Workspace workspace(String owner, WorkspaceStatus status) {
        Workspace workspace = new Workspace();
        workspace.setSpec(new WorkspaceSpec(TEST_WORKSPACE, "label", APP_DEFINITION, owner));
        workspace.setStatus(status);
        return workspace;
    }

    private LaunchRequest launchRequest(String requestUser) {
        LaunchRequest request = new LaunchRequest();
        request.appId = APP_ID;
        request.user = requestUser;
        request.appDefinition = APP_DEFINITION;
        request.workspaceName = TEST_WORKSPACE;
        return request;
    }

    private void assertForbidden(Executable launch) {
        TheiaCloudWebException exception = assertThrows(TheiaCloudWebException.class, launch);
        assertEquals(Status.FORBIDDEN.getStatusCode(), exception.getResponse().getStatus());
    }

    private void verifyNoSessionAndNoDeletion() {
        Mockito.verify(k8sUtil, never()).launchWorkspaceSession(anyString(), any(), anyInt(), any());
        Mockito.verify(k8sUtil, never()).deleteWorkspace(anyString(), anyString());
    }

    private void verifyWorkspaceSessionStartedFor(String owner) {
        Mockito.verify(k8sUtil).launchWorkspaceSession(anyString(),
                Mockito.argThat(workspace -> owner.equals(workspace.user)), anyInt(), any());
        Mockito.verify(k8sUtil, never()).deleteWorkspace(anyString(), anyString());
    }

    private void mockUser(boolean anonymous, String name) {
        Mockito.when(user.isAnonymous()).thenReturn(anonymous);
        Mockito.when(user.getIdentifier()).thenReturn(name);
    }
}
