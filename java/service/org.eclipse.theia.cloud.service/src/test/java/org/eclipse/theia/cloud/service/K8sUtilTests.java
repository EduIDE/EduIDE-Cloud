/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.client.AppDefinitionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.SidecarSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;
import org.eclipse.theia.cloud.common.util.WorkspaceUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import jakarta.ws.rs.core.Response.Status;

class K8sUtilTests {

    @Test
    void launchEphemeralSession_allowsSidecarsWithoutWorkspaceMount() throws Exception {
        K8sUtil util = createK8sUtil();
        AppDefinition appDefinition = createAppDefinition(false);

        Mockito.when(util.CLIENT.appDefinitions().get("test-app")).thenReturn(Optional.of(appDefinition));
        Mockito.when(util.CLIENT.sessions().launch(anyString(), any(), anyInt())).thenReturn(launchedSession("https://example.test"));

        String url = util.launchEphemeralSession("corr", "test-app", "alice", 3, null);

        assertEquals("https://example.test", url);
        Mockito.verify(util.CLIENT.sessions()).launch(anyString(), any(), anyInt());
    }

    @Test
    void launchEphemeralSession_rejectsSidecarsThatMountWorkspace() throws Exception {
        K8sUtil util = createK8sUtil();
        AppDefinition appDefinition = createAppDefinition(true);

        Mockito.when(util.CLIENT.appDefinitions().get("test-app")).thenReturn(Optional.of(appDefinition));

        TheiaCloudWebException exception = assertThrows(TheiaCloudWebException.class,
                () -> util.launchEphemeralSession("corr", "test-app", "alice", 3, null));

        assertEquals(Status.BAD_REQUEST.getStatusCode(), exception.getResponse().getStatus());
        Mockito.verify(util.CLIENT.sessions(), never()).launch(anyString(), any(), anyInt());
    }

    @Test
    void launchEphemeralSession_namesTheSessionAfterTheRepository() throws Exception {
        String repoA = launchedSessionName(env("https://github.com/octocat/Hello-World.git"));
        String repoB = launchedSessionName(env("https://github.com/octocat/Spoon-Knife.git"));
        String repoAAgain = launchedSessionName(env("https://GitHub.com/octocat/Hello-World/"));

        assertNotEquals(repoA, repoB, "a second repository must not reopen the first one's session");
        assertEquals(repoA, repoAAgain, "the same repository reopens its own session");
    }

    @Test
    void launchEphemeralSession_ignoresCredentialsInTheRepository() throws Exception {
        String withToken = launchedSessionName(env("https://student1:token-1@artemis.example.org/git/EX/ex-student1.git"));
        String newToken = launchedSessionName(env("https://student1:token-2@artemis.example.org/git/EX/ex-student1.git"));

        assertEquals(withToken, newToken);
        assertFalse(withToken.contains("token"), withToken);
    }

    @Test
    void launchEphemeralSession_withoutRepositoryKeepsTheUserAndAppName() throws Exception {
        assertEquals(WorkspaceUtil.getSessionName("alice", "test-app", false), launchedSessionName(null));
        assertEquals(WorkspaceUtil.getSessionName("alice", "test-app", false),
                launchedSessionName(envOf(Map.of("THEIA", "true"))));
    }

    @Test
    void repositoryOf_normalizesTheGitUri() {
        assertEquals("https://github.com/octocat/Hello-World",
                K8sUtil.repositoryOf(env(" https://user:secret@GitHub.COM:443/octocat/Hello-World.git/ ")));
        assertEquals("http://localhost:7990/scm/ex/repo", K8sUtil.repositoryOf(env("http://localhost:7990/scm/ex/repo")));
        assertEquals("git@github.com:octocat/Hello-World.git",
                K8sUtil.repositoryOf(env("git@github.com:octocat/Hello-World.git")));
        assertNull(K8sUtil.repositoryOf(null));
        assertNull(K8sUtil.repositoryOf(env("   ")));
        assertNull(K8sUtil.repositoryOf(envOf(Map.of())));
    }

    private String launchedSessionName(EnvironmentVars env) throws Exception {
        K8sUtil util = createK8sUtil();
        Mockito.when(util.CLIENT.appDefinitions().get("test-app")).thenReturn(Optional.empty());
        Mockito.when(util.CLIENT.sessions().launch(anyString(), any(), anyInt())).thenReturn(launchedSession("https://example.test"));

        util.launchEphemeralSession("corr", "test-app", "alice", 3, env);

        ArgumentCaptor<SessionSpec> spec = ArgumentCaptor.forClass(SessionSpec.class);
        Mockito.verify(util.CLIENT.sessions()).launch(anyString(), spec.capture(), anyInt());
        return spec.getValue().getName();
    }

    private EnvironmentVars env(String gitUri) {
        return envOf(Map.of("GIT_URI", gitUri));
    }

    private EnvironmentVars envOf(Map<String, String> fromMap) {
        EnvironmentVars env = new EnvironmentVars();
        env.fromMap = fromMap;
        return env;
    }

    private K8sUtil createK8sUtil() {
        K8sUtil util = new K8sUtil();
        TheiaCloudClient client = Mockito.mock(TheiaCloudClient.class);
        AppDefinitionResourceClient appDefinitions = Mockito.mock(AppDefinitionResourceClient.class);
        SessionResourceClient sessions = Mockito.mock(SessionResourceClient.class);

        util.CLIENT = client;
        Mockito.when(client.appDefinitions()).thenReturn(appDefinitions);
        Mockito.when(client.sessions()).thenReturn(sessions);
        return util;
    }

    private AppDefinition createAppDefinition(boolean mountWorkspace) throws Exception {
        AppDefinitionSpec spec = new AppDefinitionSpec();
        SidecarSpec sidecar = new SidecarSpec();
        setField(sidecar, "mountWorkspace", mountWorkspace);
        setField(spec, "sidecars", List.of(sidecar));

        AppDefinition appDefinition = new AppDefinition();
        appDefinition.setSpec(spec);
        return appDefinition;
    }

    private Session launchedSession(String url) {
        SessionStatus status = new SessionStatus();
        status.setUrl(url);

        Session session = new Session();
        session.setStatus(status);
        return session;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
