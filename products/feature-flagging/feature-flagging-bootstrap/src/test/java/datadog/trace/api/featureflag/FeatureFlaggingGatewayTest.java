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
  private FeatureFlaggingGateway.OfflineConfigListener offlineConfigListener;
  private FeatureFlaggingGateway.ExposureListener exposureListener;
  private ServerConfiguration firstConfiguration;
  private ServerConfiguration secondConfiguration;
  private ExposureEvent firstExposure;
  private ExposureEvent secondExposure;

  @BeforeEach
  void setUp() {
    configListener = mock(FeatureFlaggingGateway.ConfigListener.class);
    offlineConfigListener = mock(FeatureFlaggingGateway.OfflineConfigListener.class);
    exposureListener = mock(FeatureFlaggingGateway.ExposureListener.class);
    firstConfiguration = mock(ServerConfiguration.class);
    secondConfiguration = mock(ServerConfiguration.class);
    firstExposure = mock(ExposureEvent.class);
    secondExposure = mock(ExposureEvent.class);
  }

  @AfterEach
  void tearDown() {
    FeatureFlaggingGateway.removeConfigListener(configListener);
    FeatureFlaggingGateway.removeOfflineConfigListener(offlineConfigListener);
    FeatureFlaggingGateway.removeExposureListener(exposureListener);
  }

  @Test
  void testDispatchingOfflineConfiguration() {
    final byte[] configuration = {1, 2, 3};

    assertFalse(FeatureFlaggingGateway.dispatchOfflineConfiguration(configuration));

    FeatureFlaggingGateway.addOfflineConfigListener(offlineConfigListener);

    assertTrue(FeatureFlaggingGateway.dispatchOfflineConfiguration(configuration));
    verify(offlineConfigListener).accept(configuration);
    verifyNoMoreInteractions(offlineConfigListener);
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

  private static void clearCurrentServerConfiguration() {
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }
}
