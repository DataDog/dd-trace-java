package com.datadog.openfeature.remoteconfig;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.featureflag.RemoteConfigTransport.ConfigurationListener;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

@ExtendWith(WithConfigExtension.class)
class RemoteConfigExtensionTest {
  @Test
  @WithConfig(key = "remote_configuration.enabled", value = "true")
  void concreteFactoryCanCloseBeforeActivation() {
    RemoteConfigExtension.create().close();
  }

  @Test
  @WithConfig(key = "remote_configuration.enabled", value = "false")
  void concreteFactoryRejectsDisabledRemoteConfiguration() {
    assertThrows(IllegalStateException.class, RemoteConfigExtension::create);
  }

  @Test
  void registersOnceForwardsBytesAndRemovalAndStopsOnce() throws Exception {
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    ConfigurationListener listener = mock(ConfigurationListener.class);
    RemoteConfigExtension extension = RemoteConfigExtension.create(poller, ignored -> null);
    extension.start(listener);
    extension.start(listener);
    ArgumentCaptor<ConfigurationChangesListener> callback =
        ArgumentCaptor.forClass(ConfigurationChangesListener.class);
    verify(poller).addListener(eq(Product.FFE_FLAGS), callback.capture());
    byte[] configuration = new byte[] {1, 2, 3};
    callback.getValue().accept("key", configuration, null);
    callback.getValue().accept("key", null, null);
    verify(listener).accept(configuration);
    verify(listener).accept(null);
    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).start();
    extension.close();
    extension.close();
    verify(poller).removeListeners(Product.FFE_FLAGS);
    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).stop();
    assertThrows(IllegalStateException.class, () -> extension.start(listener));
  }

  @Test
  void unusedExtensionDoesNotStartPollingOrDiscoverProxy() {
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    Function<Boolean, BackendApi> factory = mock(Function.class);
    RemoteConfigExtension extension = RemoteConfigExtension.create(poller, factory);
    extension.close();
    verifyNoInteractions(poller, factory);
  }

  @Test
  void retriesUnavailableProxyWithoutDirectFallbackAndCachesBothEncodings() throws Exception {
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    Function<Boolean, BackendApi> factory = mock(Function.class);
    BackendApi compressed = mock(BackendApi.class);
    BackendApi plain = mock(BackendApi.class);
    when(factory.apply(true)).thenReturn(null, compressed);
    when(factory.apply(false)).thenReturn(plain);
    RemoteConfigExtension extension = RemoteConfigExtension.create(poller, factory);
    byte[] payload = new byte[0];
    assertThrows(IOException.class, () -> extension.post("exposures", payload, true));
    extension.post("exposures", payload, true);
    extension.post("exposures", payload, true);
    extension.post("flagevaluation", payload, false);
    extension.post("flagevaluation", payload, false);
    verify(factory, times(2)).apply(true);
    verify(factory).apply(false);
    verify(compressed, times(2)).post(eq("exposures"), any(), any(), eq(null), eq(false));
    verify(plain, times(2)).post(eq("flagevaluation"), any(), any(), eq(null), eq(false));
    extension.close();
    assertThrows(IOException.class, () -> extension.post("exposures", payload, true));
  }
}
