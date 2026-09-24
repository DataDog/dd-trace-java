package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ProviderRuntimeTest {
  private final ConfigurationSourceService source = mock(ConfigurationSourceService.class);
  private final ExposureWriter exposures = mock(ExposureWriter.class);
  private final FlagEvaluationWriter evaluations = mock(FlagEvaluationWriter.class);

  @AfterEach
  void resetGateway() {
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  void startsAndClosesEachResourceOnce() {
    ProviderRuntime runtime = ProviderRuntime.start(source, exposures, () -> evaluations, true);
    assertTrue(runtime.hasConfigurationSource());
    assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    runtime.close();
    runtime.close();
    InOrder order = inOrder(source, exposures, evaluations);
    order.verify(source).init();
    order.verify(exposures).init();
    order.verify(evaluations).start();
    order.verify(evaluations).close();
    order.verify(exposures).close();
    order.verify(source).close();
    order.verifyNoMoreInteractions();
    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
  }

  @Test
  void disabledEvaluationWriterIsNotConstructed() {
    Supplier<FlagEvaluationWriter> factory = mock(Supplier.class);
    try (ProviderRuntime runtime = ProviderRuntime.start(source, exposures, factory, false)) {
      assertTrue(runtime.hasConfigurationSource());
      assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
      verifyNoInteractions(factory);
    }
  }

  @Test
  void supportsNoConfigurationSource() {
    try (ProviderRuntime runtime = ProviderRuntime.start(null, exposures, null, false)) {
      assertFalse(runtime.hasConfigurationSource());
    }
    verify(exposures).close();
  }

  @Test
  void sourceFailureClosesOwnedResources() {
    IllegalStateException failure = new IllegalStateException("source failed");
    doThrow(failure).when(source).init();
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> ProviderRuntime.start(source, exposures, () -> evaluations, true)));
    verify(exposures).close();
    verify(source).close();
    verifyNoInteractions(evaluations);
  }

  @Test
  void evaluationFactoryFailureRollsBackStartedResources() {
    IllegalStateException failure = new IllegalStateException("factory failed");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                ProviderRuntime.start(
                    source,
                    exposures,
                    () -> {
                      throw failure;
                    },
                    true)));
    verify(exposures).close();
    verify(source).close();
    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
  }

  @Test
  void evaluationStartErrorClosesThePartiallyStartedWriter() {
    AssertionError failure = new AssertionError("writer failed");
    doThrow(failure).when(evaluations).start();
    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () -> ProviderRuntime.start(source, exposures, () -> evaluations, true)));
    verify(evaluations).close();
    verify(exposures).close();
    verify(source).close();
  }

  @Test
  void cleanupExceptionDoesNotHideStartupFailureOrSkipOtherResources() {
    IllegalStateException failure = new IllegalStateException("writer failed");
    doThrow(failure).when(exposures).init();
    doThrow(new IllegalArgumentException("close failed")).when(exposures).close();
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> ProviderRuntime.start(source, exposures, () -> evaluations, true)));
    verify(source).close();
  }
}
