package com.datadog.spark;

import datadog.trace.bootstrap.instrumentation.spark.SparkAgentContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.dynamic.loading.ClassInjector;

public class SparkAgent implements SparkAgentContext.SparkAgentIntf {

  public static synchronized void run(Instrumentation instrumentation) {
    System.err.println("Starting SparkAgent...");
    SparkAgentContext.init(new SparkAgent());
    instrumentation.addTransformer(new SparkTransformer());
  }

  @Override
  public void sendMetric(long value) {
    System.err.println("sending metrics: " + value);
    // FIXME use DogStatsd to send metrics
  }

  @Override
  public void register(ClassLoader sparkClassLoader) {
    System.err.println("SparkAgent.register");
    byte[] classFileBuffer;
    try (InputStream inputStream =
        getClass().getResourceAsStream("/com/datadog/spark/DDSparkListener.classdata")) {
      if (inputStream == null) {
        System.err.println("cannot find DDSparkListener");
        return;
      }
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int byteLen;
      while ((byteLen = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, byteLen);
      }
      classFileBuffer = outputStream.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    Map<String, byte[]> types = new HashMap<>();
    types.put("com.datadog.spark.DDSparkListener", classFileBuffer);
    ClassInjector.UsingReflection classInjector =
        new ClassInjector.UsingReflection(sparkClassLoader);
    classInjector.injectRaw(types);
  }
}
