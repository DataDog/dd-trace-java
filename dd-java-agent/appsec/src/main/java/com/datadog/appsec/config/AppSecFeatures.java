package com.datadog.appsec.config;

public class AppSecFeatures {
  public Asm asm;

  @com.squareup.moshi.Json(name = "api_security")
  public ApiSecurity apiSecurity;

  public static class Asm {
    public boolean enabled;
  }

  public static class ApiSecurity {
    @com.squareup.moshi.Json(name = "request_sample_rate")
    public Float requestSampleRate;
  }
}
