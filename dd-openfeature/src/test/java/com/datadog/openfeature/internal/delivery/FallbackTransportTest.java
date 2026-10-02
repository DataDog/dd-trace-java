package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datadog.openfeature.internal.connector.EventProxyUnavailableException;
import com.datadog.openfeature.internal.connector.EventTransport;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class FallbackTransportTest {
  private static final byte[] PAYLOAD = "{}".getBytes(UTF_8);

  private final EventTransport proxy = mock(EventTransport.class);
  private final EventTransport direct = mock(EventTransport.class);
  private final FallbackTransport transport = new FallbackTransport(this.proxy, this.direct);

  @Test
  void postsThroughTheProxy() throws IOException {
    this.transport.post("exposures", PAYLOAD);

    verify(this.proxy).post("exposures", PAYLOAD);
    verify(this.direct, never()).post("exposures", PAYLOAD);
  }

  @Test
  void switchesToDirectIntakeForGoodWhenTheProxyIsUnavailable() throws IOException {
    doThrow(new EventProxyUnavailableException("unavailable"))
        .when(this.proxy)
        .post("exposures", PAYLOAD);

    this.transport.post("exposures", PAYLOAD);
    this.transport.post("exposures", PAYLOAD);

    verify(this.proxy, times(1)).post("exposures", PAYLOAD);
    verify(this.direct, times(2)).post("exposures", PAYLOAD);
  }

  @Test
  void doesNotReplayAmbiguousProxyFailures() throws IOException {
    doThrow(new IOException("timeout")).when(this.proxy).post("exposures", PAYLOAD);

    assertThrows(IOException.class, () -> this.transport.post("exposures", PAYLOAD));

    verify(this.direct, never()).post("exposures", PAYLOAD);
  }
}
