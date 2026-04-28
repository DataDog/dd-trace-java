package datadog.trace.core.otlp.common;

import java.io.IOException;

/** Receives chunks of OTLP data. */
@FunctionalInterface
public interface OtlpSink {
  void write(byte[] chunk) throws IOException;
}
