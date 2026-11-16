package org.eclipse.theia.cloud.service.appdefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.service.ApplicationProperties;
import org.eclipse.theia.cloud.service.K8sUtil;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;

@QuarkusTest
@TestSecurity(authorizationEnabled = false)
class AppDefinitionResourceTests {

    private static final String APP_ID = "asdfghjkl";

    @InjectMock
    ApplicationProperties applicationProperties;

    @InjectMock
    K8sUtil k8sUtil;

    @Inject
    AppDefinitionResource fixture;

    @Test
    void list_returnsOnlyNames() {
        AppDefinitionSpec spec = Mockito.mock(AppDefinitionSpec.class);
        Mockito.when(spec.getName()).thenReturn("python");
        Mockito.when(k8sUtil.listAppDefinitions()).thenReturn(List.of(spec));

        List<AppDefinitionSummary> result = fixture.list(APP_ID);

        assertEquals(1, result.size());
        assertEquals("python", result.get(0).name);
        assertEquals(1, AppDefinitionSummary.class.getFields().length);
    }
}
