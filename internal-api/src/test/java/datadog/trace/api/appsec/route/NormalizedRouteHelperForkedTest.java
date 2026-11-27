package datadog.trace.api.appsec.route;

import static datadog.trace.api.KnownTags.DD_APPSEC_NORMALIZED_ROUTE;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENABLED;
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENABLED_EXPERIMENTAL;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Mutates the global {@link ActiveSubsystems#APPSEC_ACTIVE}, so it runs in its own JVM. */
@ExtendWith(WithConfigExtension.class)
class NormalizedRouteHelperForkedTest {

  private boolean previousAppSecActive;

  @BeforeEach
  void activateAppSec() {
    previousAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = previousAppSecActive;
  }

  @Test
  void enabledWhenAppSecIsActiveAndApiSecurityByDefault() {
    assertTrue(NormalizedRouteHelper.isEnabled());

    AgentSpan span = spanWithRoute();
    NormalizedRouteHelper.tag(span, SpringRouteParser.parse("/users/{id}"), null, null);

    verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/users/{id}");
  }

  @Test
  void disabledWhenAppSecIsInactive() {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    assertFalse(NormalizedRouteHelper.isEnabled());

    AgentSpan span = spanWithRoute();
    NormalizedRouteHelper.tag(span, SpringRouteParser.parse("/users/{id}"), null, null);

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {API_SECURITY_ENABLED, API_SECURITY_ENABLED_EXPERIMENTAL})
  void disabledWhenApiSecurityIsDisabled(String setting) {
    injectSysConfig(setting, "false");

    assertFalse(NormalizedRouteHelper.isEnabled());
  }

  private static AgentSpan spanWithRoute() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(Tags.HTTP_ROUTE)).thenReturn("/users/{id}");
    return span;
  }
}
