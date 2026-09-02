package com.datadog.profiling.otel;

import datadog.trace.api.profiling.RecordingData;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Main entry point for converting JFR recordings to OTLP profiles format. This class provides
 * methods to convert RecordingData to both binary protobuf and JSON formats.
 */
public final class OtlpProfileWriter {

  private final JfrToOtlpConverter converter;

  public OtlpProfileWriter() {
    this.converter = new JfrToOtlpConverter();
  }

  public byte[] writeProtobuf(RecordingData data) throws IOException {
    return converter.addRecording(data).convert();
  }

  public void writeProtobuf(RecordingData data, OutputStream out) throws IOException {
    byte[] protobuf = writeProtobuf(data);
    out.write(protobuf);
  }

  public String writeJson(RecordingData data) throws IOException {
    byte[] json = converter.addRecording(data).convert(JfrToOtlpConverter.Kind.JSON);
    return new String(json, java.nio.charset.StandardCharsets.UTF_8);
  }
}
