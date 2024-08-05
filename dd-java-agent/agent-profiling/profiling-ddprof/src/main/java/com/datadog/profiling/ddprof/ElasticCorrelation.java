package com.datadog.profiling.ddprof;

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
  //  private static ByteBuffer
  private ElasticCorrelation() {
    synchronized (ElasticCorrelation.class) {
      String correlationSocketPath = openCorrelationSocket();
      assert correlationSocketPath != null; // temporary check, different API in the long term
      UniversalProfilingCorrelation.setProcessStorage(
          generateProcessCorrelationStorage(correlationSocketPath));
    }
    // set register bytebuffers for each thread?
  }

  public static ElasticCorrelation getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new ElasticCorrelation();
    }
    return INSTANCE;
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
