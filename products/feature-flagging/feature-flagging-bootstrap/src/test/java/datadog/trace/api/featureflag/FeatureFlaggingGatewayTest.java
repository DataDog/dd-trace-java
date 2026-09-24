package datadog.trace.api.featureflag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeatureFlaggingGatewayTest {

  private FeatureFlaggingGateway.ConfigListener configListener;
  private FeatureFlaggingGateway.ActivationListener activationListener;
  private FeatureFlaggingGateway.ExposureListener exposureListener;
  private FeatureFlaggingGateway.SpanEnrichmentListener spanEnrichmentListener;
  private ServerConfiguration firstConfiguration;
  private ServerConfiguration secondConfiguration;
  private ExposureEvent firstExposure;
  private ExposureEvent secondExposure;

  @BeforeEach
  void setUp() {
    configListener = mock(FeatureFlaggingGateway.ConfigListener.class);
    activationListener = mock(FeatureFlaggingGateway.ActivationListener.class);
    exposureListener = mock(FeatureFlaggingGateway.ExposureListener.class);
    spanEnrichmentListener = mock(FeatureFlaggingGateway.SpanEnrichmentListener.class);
    firstConfiguration = mock(ServerConfiguration.class);
    secondConfiguration = mock(ServerConfiguration.class);
    firstExposure = mock(ExposureEvent.class);
    secondExposure = mock(ExposureEvent.class);
  }

  @AfterEach
  void tearDown() {
    FeatureFlaggingGateway.removeConfigListener(configListener);
    FeatureFlaggingGateway.removeActivationListener(activationListener);
    FeatureFlaggingGateway.removeExposureListener(exposureListener);
    FeatureFlaggingGateway.removeSpanEnrichmentListener(spanEnrichmentListener);
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  void testProviderActivationListener() {
    FeatureFlaggingGateway.addActivationListener(activationListener);

    FeatureFlaggingGateway.activate();

    verify(activationListener).activate();
    verifyNoMoreInteractions(activationListener);
  }

  @Test
  void testAttachingAConfigListener() {
    clearCurrentServerConfiguration();

    FeatureFlaggingGateway.addConfigListener(configListener);
    FeatureFlaggingGateway.dispatch(firstConfiguration);

    verify(configListener).accept(firstConfiguration);
    verifyNoMoreInteractions(configListener);

    FeatureFlaggingGateway.dispatch(secondConfiguration);

    verify(configListener).accept(secondConfiguration);
    verifyNoMoreInteractions(configListener);
  }

  @Test
  void testAttachingAListenerAfterConfigured() {
    FeatureFlaggingGateway.dispatch(firstConfiguration);
    FeatureFlaggingGateway.addConfigListener(configListener);

    verify(configListener).accept(firstConfiguration);
    verifyNoMoreInteractions(configListener);
  }

  @Test
  void testAttachingAnExposureListener() {
    FeatureFlaggingGateway.addExposureListener(exposureListener);
    FeatureFlaggingGateway.dispatch(firstExposure);

    verify(exposureListener).accept(firstExposure);
    verifyNoMoreInteractions(exposureListener);

    FeatureFlaggingGateway.dispatch(secondExposure);

    verify(exposureListener).accept(secondExposure);
    verifyNoMoreInteractions(exposureListener);
  }

  @Test
  void testExposureAdmissionUsesRegisteredListeners() {
    assertFalse(
        FeatureFlaggingGateway.shouldCaptureExposure("flag", "subject", "variant", "allocation"));

    final FeatureFlaggingGateway.ExposureListener rejectingListener =
        new FeatureFlaggingGateway.ExposureListener() {
          @Override
          public boolean shouldCapture(
              final String flag,
              final String subject,
              final String variant,
              final String allocation) {
            return false;
          }

          @Override
          public void accept(final ExposureEvent event) {}
        };
    FeatureFlaggingGateway.addExposureListener(rejectingListener);
    try {
      assertFalse(
          FeatureFlaggingGateway.shouldCaptureExposure("flag", "subject", "variant", "allocation"));
    } finally {
      FeatureFlaggingGateway.removeExposureListener(rejectingListener);
    }

    final FeatureFlaggingGateway.ExposureListener listener = event -> {};
    FeatureFlaggingGateway.addExposureListener(listener);
    try {
      assertTrue(
          FeatureFlaggingGateway.shouldCaptureExposure("flag", "subject", "variant", "allocation"));
    } finally {
      FeatureFlaggingGateway.removeExposureListener(listener);
    }
  }

  @Test
  void testAttachingASpanEnrichmentListener() {
    final SpanEnrichmentEvent firstEvent = SpanEnrichmentEvent.serialId(42, true, "user-1");
    final SpanEnrichmentEvent secondEvent = SpanEnrichmentEvent.runtimeDefault("flag", "value");

    FeatureFlaggingGateway.addSpanEnrichmentListener(spanEnrichmentListener);
    FeatureFlaggingGateway.dispatch(firstEvent);

    verify(spanEnrichmentListener).accept(firstEvent);
    verifyNoMoreInteractions(spanEnrichmentListener);

    FeatureFlaggingGateway.dispatch(secondEvent);

    verify(spanEnrichmentListener).accept(secondEvent);
    verifyNoMoreInteractions(spanEnrichmentListener);
  }

  private static void clearCurrentServerConfiguration() {
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }
}
