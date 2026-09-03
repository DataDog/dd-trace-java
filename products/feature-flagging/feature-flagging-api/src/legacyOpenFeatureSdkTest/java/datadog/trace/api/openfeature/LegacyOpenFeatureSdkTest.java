package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.openfeature.Provider.Options;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LegacyOpenFeatureSdkTest {

  @Test
  void unsupportedSdkLinkageFailureIsContainedAndReportedOnce() throws Exception {
    assertEquals("1.15.1", Provider.openFeatureSdkVersion());
    final AtomicInteger compatibilityWarnings = new AtomicInteger();
    final AtomicReference<LinkageError> reportedError = new AtomicReference<>();
    final Provider provider =
        new Provider(new Options(), configuredEvaluator(), Boolean.FALSE) {
          @Override
          void reportOpenFeatureSdkIncompatibility(final LinkageError error) {
            compatibilityWarnings.incrementAndGet();
            reportedError.set(error);
          }
        };
    provider.initialize(null);

    assertDoesNotThrow(provider::onConfigurationChange);
    assertDoesNotThrow(provider::onConfigurationChange);

    assertEquals(1, compatibilityWarnings.get());
    assertInstanceOf(NoSuchMethodError.class, reportedError.get());
    assertTrue(
        reportedError.get().getMessage().contains("datadog.trace.api.openfeature.Provider.emit"),
        reportedError.get()::toString);
    assertTrue(
        Provider.openFeatureSdkCompatibilityWarning(reportedError.get())
            .contains("detected version: 1.15.1"));
  }

  private static Evaluator configuredEvaluator() {
    return new Evaluator() {
      @Override
      public boolean initialize(
          final long timeout, final TimeUnit timeUnit, final EvaluationContext context) {
        return true;
      }

      @Override
      public boolean hasConfiguration() {
        return true;
      }

      @Override
      public void shutdown() {}

      @Override
      public <T> ProviderEvaluation<T> evaluate(
          final Class<T> target,
          final String key,
          final T defaultValue,
          final EvaluationContext context) {
        return null;
      }
    };
  }
}
