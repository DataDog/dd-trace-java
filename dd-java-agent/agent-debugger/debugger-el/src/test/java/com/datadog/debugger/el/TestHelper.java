package com.datadog.debugger.el;

import datadog.trace.api.Config;
import java.lang.reflect.Field;

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
}
