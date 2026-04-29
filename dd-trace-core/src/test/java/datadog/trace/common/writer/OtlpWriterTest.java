package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import datadog.trace.api.config.OtlpConfig;
import datadog.trace.core.otlp.common.OtlpSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OtlpWriterTest {

  @Mock OtlpSender sender;

  @Test
  void closeShutsDownSender() {
    OtlpWriter writer = OtlpWriter.builder().sender(sender).build();
    writer.start();

    writer.close();

    verify(sender).shutdown();
  }

  @Test
  void getApisIsEmpty() {
    OtlpWriter writer = OtlpWriter.builder().sender(sender).build();
    writer.start();
    try {
      assertTrue(writer.getApis().isEmpty());
    } finally {
      writer.close();
    }
  }

  @Test
  void builderRespectsInjectedSenderAcrossProtocols() {
    for (OtlpConfig.Protocol protocol : OtlpConfig.Protocol.values()) {
      OtlpWriter writer = OtlpWriter.builder().protocol(protocol).sender(sender).build();
      writer.start();
      writer.close();
    }
    // One shutdown per writer we built.
    verify(sender, org.mockito.Mockito.times(OtlpConfig.Protocol.values().length)).shutdown();
  }

  @Test
  void buildWithDefaultsDoesNotThrow() {
    // Exercises the default-path sender construction (real OtlpHttpSender) without
    // actually sending anything — start/close only. Guards against a default config
    // that would otherwise fail at construction time.
    assertDoesNotThrow(
        () -> {
          OtlpWriter writer = OtlpWriter.builder().build();
          writer.start();
          writer.close();
        });
  }
}
