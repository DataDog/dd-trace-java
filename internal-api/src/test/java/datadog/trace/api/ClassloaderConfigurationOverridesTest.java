package datadog.trace.api;

import static datadog.trace.api.ClassloaderConfigurationOverrides.CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT;
import static datadog.trace.api.ClassloaderConfigurationOverrides.addContextualInfo;
import static datadog.trace.api.ClassloaderConfigurationOverrides.maybeEnrichSpan;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.ClassloaderConfigurationOverrides.ContextualInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class ClassloaderConfigurationOverridesTest {

  private boolean originalCanSplitServiceNameByDeployment;

  @BeforeEach
  void setup() {
    originalCanSplitServiceNameByDeployment = CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT;
  }

  @AfterEach
  void cleanup() {
    CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT = originalCanSplitServiceNameByDeployment;
  }

  @TableTest({
    "scenario                                    | splitByDeploymentEnabled | contextualServiceName | spanServiceName | expected",
    "split disabled, no names                    | false                    |                       |                 | false   ",
    "split disabled, contextual name             | false                    | test                  |                 | false   ",
    "split disabled, span name                   | false                    |                       | test            | false   ",
    "split enabled, no names                     | true                     |                       |                 | false   ",
    "split enabled, empty contextual name        | true                     | ''                    |                 | false   ",
    "split enabled, contextual name              | true                     | test                  |                 | true    ",
    "split enabled, empty contextual + span name | true                     | ''                    | test            | false   ",
    "split enabled, span name                    | true                     |                       | test            | false   ",
    "split enabled, both names set               | true                     | test                  | test            | false   "
  })
  @MethodSource("serviceNameSetWhenSplitByDeploymentArguments")
  void serviceNameSetWhenSplitByDeployment(
      boolean splitByDeploymentEnabled,
      String contextualServiceName,
      String spanServiceName,
      boolean expected) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getServiceName()).thenReturn(spanServiceName);

    CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT = splitByDeploymentEnabled;
    addContextualInfo(
        Thread.currentThread().getContextClassLoader(),
        new ContextualInfo(contextualServiceName, "test"));
    maybeEnrichSpan(span);

    if (splitByDeploymentEnabled
        && contextualServiceName != null
        && !contextualServiceName.isEmpty()) {
      verify(span, atLeastOnce()).getServiceName();
    }
    if (expected) {
      verify(span).setServiceName(contextualServiceName, "test");
    } else {
      verify(span, never()).setServiceName(contextualServiceName, "test");
    }
  }

  static Stream<Arguments> serviceNameSetWhenSplitByDeploymentArguments() {
    // span service name equal to the inferred service name is overridden
    return Stream.of(
        arguments(
            named("split enabled, span name is inferred service", true),
            "test",
            Config.get().getServiceName(),
            true));
  }

  @Test
  void enrichShouldSetTagsWhenPresent() {
    AgentSpan span = mock(AgentSpan.class);

    ClassloaderConfigurationOverrides.maybeCreateContextualInfo(
            Thread.currentThread().getContextClassLoader())
        .addTag("key", "value");
    maybeEnrichSpan(span);

    verify(span).setTag("key", (Object) "value");
  }

  @Test
  void noEnrichmentWhenContextualInfoIsAbsent() {
    AgentSpan span = mock(AgentSpan.class);

    addContextualInfo(Thread.currentThread().getContextClassLoader(), null);
    assertNull(ClassloaderConfigurationOverrides.maybeGetContextualInfo());

    assertDoesNotThrow(() -> maybeEnrichSpan(span));
  }
}
