package com.datadog.appsec.util;

public interface JvmTime {
  long nanoTime();

  enum Default implements JvmTime {
    INSTANCE;

    @Override
    public long nanoTime() {
      return System.nanoTime();
    }
  }
}
