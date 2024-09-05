package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class TestHelper {

  public static void setFieldInConfig(Config config, String fieldName, Object value) {
    try {
      Field field = config.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(config, value);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }

  public static void assertWithTimeout(BooleanSupplier predicate, Duration timeout) {
    Duration sleepTime = Duration.ofMillis(10);
    long count = timeout.toMillis() / sleepTime.toMillis();
    while (count-- > 0 && !predicate.getAsBoolean()) {
      try {
        Thread.sleep(sleepTime.toMillis());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    assertTrue(predicate.getAsBoolean());
  }

  public static void setEnvVar(String envName, String envValue) {
    try {
      Class<?> classOfMap = System.getenv().getClass();
      Field field = classOfMap.getDeclaredField("m");
      field.setAccessible(true);
      if (envValue == null) {
        ((Map<String, String>) field.get(System.getenv())).remove(envName);
      } else {
        ((Map<String, String>) field.get(System.getenv())).put(envName, envValue);
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }
}
