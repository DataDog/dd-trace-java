package com.datadog.profiling.elasticcorrelation;

import co.elastic.otel.UniversalProfilingCorrelation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class ElasticCorrelation {

  private static ElasticCorrelation INSTANCE;

  private static final int TLS_MINOR_VERSION_OFFSET = 0;
  private static final int TLS_VALID_OFFSET = 2;
  private static final int TLS_TRACE_PRESENT_OFFSET = 3;
  private static final int TLS_TRACE_FLAGS_OFFSET = 4;
  private static final int TLS_TRACE_ID_OFFSET = 5;
  private static final int TLS_SPAN_ID_OFFSET = 21;
  private static final int TLS_LOCAL_ROOT_SPAN_ID_OFFSET = 29;
  static final int TLS_STORAGE_SIZE = 37;

  private static volatile int writeForMemoryBarrier = 0;

  private ElasticCorrelation() {
    synchronized (ElasticCorrelation.class) {
      String correlationSocketPath = openCorrelationSocket();
      assert correlationSocketPath != null; // temporary check, different API in the long term
      UniversalProfilingCorrelation.setProcessStorage(
          generateProcessCorrelationStorage(correlationSocketPath));
    }
  }

  public static ElasticCorrelation getInstance() {
    if (isEnabled()) {
      INSTANCE = new ElasticCorrelation();
    }
    return INSTANCE;
  }

  public static boolean isEnabled() {
    return INSTANCE == null;
  }

  // Heavily adapted from elastic-otel-java
  public static void updateThreadCorrelationStorage(long spanId, long rootSpanId) {
    if (isEnabled()) { // not atomic, temporary
      ByteBuffer tls =
          UniversalProfilingCorrelation.getCurrentThreadStorage(true, TLS_STORAGE_SIZE);
      if (tls != null) {
        // the valid flag is used to signal the host-agent that it is reading incomplete data
        tls.put(TLS_VALID_OFFSET, (byte) 0);
        memoryStoreStoreBarrier();
        tls.putChar(TLS_MINOR_VERSION_OFFSET, (char) 1);
        tls.put(TLS_TRACE_PRESENT_OFFSET, (byte) 1);
        tls.put(TLS_TRACE_FLAGS_OFFSET, (byte) 0);
        writeHexAsBinary((CharSequence) "0", 0, tls, TLS_TRACE_ID_OFFSET, 16);
        writeHexAsBinary((CharSequence) Long.toString(spanId), 0, tls, TLS_SPAN_ID_OFFSET, 8);
        writeHexAsBinary(
            (CharSequence) Long.toString(rootSpanId), 0, tls, TLS_LOCAL_ROOT_SPAN_ID_OFFSET, 8);
        memoryStoreStoreBarrier();
        tls.put(TLS_VALID_OFFSET, (byte) 1);
      }
    }
  }

  // Copied from elastic-otel-java
  private static void writeHexAsBinary(
      CharSequence hex, int strOffset, ByteBuffer buffer, int bufferPos, int numBytes) {
    for (int i = 0; i < numBytes; i++) {
      long upper = hexCharToBinary(hex.charAt(strOffset + i * 2));
      long lower = hexCharToBinary(hex.charAt(strOffset + i * 2 + 1));
      byte byteVal = (byte) (upper << 4 | lower);
      buffer.put(bufferPos + i, byteVal);
    }
  }

  // Copied from elastic-otel-java
  private static long hexCharToBinary(char ch) {
    if ('0' <= ch && ch <= '9') {
      return ch - '0';
    }
    if ('A' <= ch && ch <= 'F') {
      return ch - 'A' + 10;
    }
    if ('a' <= ch && ch <= 'f') {
      return ch - 'a' + 10;
    }
    throw new IllegalArgumentException("Not a hex char: " + ch);
  }

  private static void memoryStoreStoreBarrier() {
    writeForMemoryBarrier = 42;
  }

  // Adapted from elastic-otel-java
  private ByteBuffer generateProcessCorrelationStorage(String correlationSocketPath) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
    buffer.order(ByteOrder.nativeOrder());
    buffer.position(0);

    buffer.putChar((char) 1); // layout-minor-version
    writeUtf8Str(buffer, "test service name");
    writeUtf8Str(buffer, "test service environment");
    writeUtf8Str(buffer, correlationSocketPath); // socket-file-path
    return buffer;
  }

  // Copied from elastic-otel-java
  private void writeUtf8Str(ByteBuffer buffer, String str) {
    byte[] utf8 = str.getBytes(StandardCharsets.UTF_8);
    buffer.putInt(utf8.length);
    buffer.put(utf8);
  }

  private String openCorrelationSocket() {
    String correlationSocketPath;
    try {
      correlationSocketPath = generateSocketPath();
      UniversalProfilingCorrelation.startProfilerReturnChannel(correlationSocketPath);
      return correlationSocketPath;
    } catch (IOException e) {
      return null;
    }
  }

  private String generateSocketPath() throws IOException {
    Path tmpDir = Files.createTempDirectory("apmCorrelationSockets").toAbsolutePath();
    Path socketFile;
    do {
      socketFile = tmpDir.resolve(randomSocketFileName());
    } while (Files.exists(socketFile));

    return socketFile.toAbsolutePath().toString();
  }

  // Copied from elastic-otel-java
  private String randomSocketFileName() {
    StringBuilder name = new StringBuilder("essock");
    String allowedChars = "abcdefghijklmonpqrstuvwxzyABCDEFGHIJKLMONPQRSTUVWXYZ0123456789";
    Random rnd = new Random();
    for (int i = 0; i < 8; i++) {
      int idx = rnd.nextInt(allowedChars.length());
      name.append(allowedChars.charAt(idx));
    }
    return name.toString();
  }
}
