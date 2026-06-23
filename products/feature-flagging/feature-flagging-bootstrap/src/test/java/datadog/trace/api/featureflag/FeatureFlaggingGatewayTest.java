package datadog.trace.api.featureflag;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeatureFlaggingGatewayTest {

  @BeforeEach
  @AfterEach
  void resetCurrentConfiguration() {
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }

  @Test
  void testAttachingAConfigListener() {
    FeatureFlaggingGateway.ConfigListener listener =
        mock(FeatureFlaggingGateway.ConfigListener.class);
    ServerConfiguration first = mock(ServerConfiguration.class);
    ServerConfiguration second = mock(ServerConfiguration.class);

    try {
      FeatureFlaggingGateway.addConfigListener(listener);
      FeatureFlaggingGateway.dispatch(first);

      verify(listener).accept(first);
      verifyNoMoreInteractions(listener);

      FeatureFlaggingGateway.dispatch(second);

      verify(listener).accept(second);
      verifyNoMoreInteractions(listener);
    } finally {
      FeatureFlaggingGateway.removeConfigListener(listener);
    }
  }

  @Test
  void testAttachingAListenerAfterConfigured() {
    FeatureFlaggingGateway.ConfigListener listener =
        mock(FeatureFlaggingGateway.ConfigListener.class);
    ServerConfiguration first = mock(ServerConfiguration.class);

    try {
      FeatureFlaggingGateway.dispatch(first);
      FeatureFlaggingGateway.addConfigListener(listener);

      verify(listener).accept(first);
      verifyNoMoreInteractions(listener);
    } finally {
      FeatureFlaggingGateway.removeConfigListener(listener);
    }
  }

  @Test
  void testAttachingAnExposureListener() {
    FeatureFlaggingGateway.ExposureListener listener =
        mock(FeatureFlaggingGateway.ExposureListener.class);
    ExposureEvent first = mock(ExposureEvent.class);
    ExposureEvent second = mock(ExposureEvent.class);

    try {
      FeatureFlaggingGateway.addExposureListener(listener);
      FeatureFlaggingGateway.dispatch(first);

      verify(listener).accept(first);
      verifyNoMoreInteractions(listener);

      FeatureFlaggingGateway.dispatch(second);

      verify(listener).accept(second);
      verifyNoMoreInteractions(listener);
    } finally {
      FeatureFlaggingGateway.removeExposureListener(listener);
    }
  }
}
