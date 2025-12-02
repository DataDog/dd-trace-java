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

  /**
   * Converts a JFR recording to OTLP binary protobuf format.
   *
   * @param data the recording data to convert
   * @return encoded OTLP ProfilesData protobuf bytes
   * @throws IOException if reading or converting fails
   */
  public byte[] writeProtobuf(RecordingData data) throws IOException {
    return converter.addRecording(data).convert();
  }

  /**
   * Converts a JFR recording to OTLP binary protobuf format and writes to an output stream.
   *
   * @param data the recording data to convert
   * @param out the output stream to write to
   * @throws IOException if reading, converting, or writing fails
   */
  public void writeProtobuf(RecordingData data, OutputStream out) throws IOException {
    byte[] protobuf = writeProtobuf(data);
    out.write(protobuf);
  }

  /**
   * Converts a JFR recording to OTLP JSON format (for debugging).
   *
   * @param data the recording data to convert
   * @return JSON string representation of the OTLP ProfilesData
   * @throws IOException if reading or converting fails
   */
  public String writeJson(RecordingData data) throws IOException {
    // JSON encoding will be implemented in Phase 5
    throw new UnsupportedOperationException("JSON output not yet implemented");
  }
}
