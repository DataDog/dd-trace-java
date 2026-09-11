package datadog.telemetry;

import static datadog.trace.api.config.GeneralConfig.TELEMETRY_DEPENDENCY_COLLECTION_ENABLED;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.telemetry.dependency.DependencyService;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
@WithConfig(key = TELEMETRY_DEPENDENCY_COLLECTION_ENABLED, value = "false")
class DisabledDependencyServiceTest {

  @Test
  void installsDisabledDependencyServiceAndVerifyTransformer() {
    Instrumentation instrumentation = mock(Instrumentation.class);

    DependencyService dependencyService = TelemetrySystem.createDependencyService(instrumentation);

    verifyNoInteractions(instrumentation);
    assertNull(dependencyService);
  }
}
