package com.datadog.appsec.config;

public class AppSecFeatures {
  public Asm asm;

  @com.squareup.moshi.Json(name = "api_security")
  public ApiSecurity apiSecurity;

  @com.squareup.moshi.Json(name = "auto_user_instrum")
  public AutoUserInstrum autoUserInstrum;

  public static class Asm {
    public Boolean enabled;

    @Override
    public String toString() {
      return "Asm{" + "enabled=" + enabled + '}';
    }
  }

  public static class ApiSecurity {
    @com.squareup.moshi.Json(name = "request_sample_rate")
    public Float requestSampleRate;

    @Override
    public String toString() {
      return "ApiSecurity{" + "requestSampleRate=" + requestSampleRate + '}';
    }
  }

  public static class AutoUserInstrum {
    public String mode;

    @Override
    public String toString() {
      return "AutoUserInstrum{" + "mode=" + mode + '}';
    }
  }

  @Override
  public String toString() {
    return "AppSecFeatures{"
        + "asm="
        + asm
        + ", apiSecurity="
        + apiSecurity
        + ", autoUserInstrum="
        + autoUserInstrum
        + '}';
  }
}
