package com.datadog.spark;

import datadog.communication.monitor.DDAgentStatsDClientManager;
import datadog.trace.api.StatsDClient;
import datadog.trace.bootstrap.instrumentation.spark.SparkAgentContext;
import net.bytebuddy.dynamic.loading.ClassInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;

public class SparkAgent implements SparkAgentContext.SparkAgentIntf {
  private static final Logger log = LoggerFactory.getLogger(SparkAgent.class);

  public SparkAgent() {}

  public static synchronized void run(Instrumentation instrumentation) {
    log.info("Starting SparkAgent...");
    SparkAgentContext.init(new SparkAgent());
    instrumentation.addTransformer(new SparkTransformer());
  }

  @Override
  public void register(ClassLoader sparkClassLoader) {
    log.info("SparkAgent.register");

    byte[] ddSparkTaskMetrics = getClassFileBuffer("/com/datadog/spark/DDSparkTaskMetrics.classdata");
    byte[] ddSparkListener = getClassFileBuffer("/com/datadog/spark/DDSparkListener.classdata");

    Map<String, byte[]> types = new HashMap<>();
    types.put("com.datadog.spark.DDSparkTaskMetrics", ddSparkTaskMetrics);
    types.put("com.datadog.spark.DDSparkListener", ddSparkListener);
    ClassInjector.UsingReflection classInjector = new ClassInjector.UsingReflection(sparkClassLoader);
    classInjector.injectRaw(types);
  }

  private byte[] getClassFileBuffer(String name) {
    try (InputStream inputStream = getClass().getResourceAsStream(name)) {
      if (inputStream == null) {
        log.info("Cannot find class " + name);
        return new byte[0];
      }
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int byteLen;
      while ((byteLen = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, byteLen);
      }
      return outputStream.toByteArray();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
