package com.datadog.profiling.ddprof;

import co.elastic.otel.UniversalProfilingCorrelation;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class ElasticCorrelation {

  private static ElasticCorrelation INSTANCE;
//  private static ByteBuffer
  private ElasticCorrelation() {
    synchronized(ElasticCorrelation.class) {
      openCorrelationSocket();
    }
    // open socket
    // set bytebuffer for process info
    // set register bytebuffers for each thread?
  }

  public static ElasticCorrelation getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new ElasticCorrelation();
    }
    return INSTANCE;
  }

  private void openCorrelationSocket(){
    String correlationSocketPath;
    try {
      correlationSocketPath = generateSocketPath();
      UniversalProfilingCorrelation.startProfilerReturnChannel(correlationSocketPath);
    } catch (IOException e) {
      // Not good, debug
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
