package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.realtime.chat.config.DemoDataConfig;
import com.realtime.chat.config.E2eHandshakeInstanceInterceptor;
import com.realtime.chat.controller.DemoController;
import com.realtime.chat.service.DemoPersistenceFailureProbe;
import com.realtime.chat.service.NoopPersistenceFailureProbe;
import com.realtime.chat.service.PersistenceFailureProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"prod", "e2e"})
class ProdE2eProfileBoundaryIntegrationTest extends BaseIntegrationTest {

  @Autowired private ApplicationContext context;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void prodProfileDisablesEveryE2eOnlyBeanEvenWhenE2eIsAlsoActive() {
    assertThat(context.getEnvironment().getActiveProfiles())
        .contains("test", "prod", "e2e");
    assertThat(context.getBeansOfType(DemoDataConfig.class)).isEmpty();
    assertThat(context.getBeansOfType(DemoController.class)).isEmpty();
    assertThat(context.getBeansOfType(DemoPersistenceFailureProbe.class)).isEmpty();
    assertThat(context.getBeansOfType(E2eHandshakeInstanceInterceptor.class)).isEmpty();
    assertThat(context.getBeansOfType(PersistenceFailureProbe.class))
        .hasSize(1)
        .allSatisfy(
            (name, probe) ->
                assertThat(probe).isInstanceOf(NoopPersistenceFailureProbe.class));
  }

  @Test
  void prodAndE2eProfilesDoNotExposeE2eDiagnosticsEndpoint() {
    assertThat(restTemplate.getForEntity("/api/demo/instance", String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
