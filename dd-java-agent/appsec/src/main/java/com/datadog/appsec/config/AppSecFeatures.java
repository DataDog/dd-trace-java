package com.datadog.appsec.config;

public class AppSecFeatures {
  public Asm asm;

  @com.squareup.moshi.Json(name = "auto_user_instrum")
  public AutoUserInstrum autoUserInstrum;

  public static class Asm {
    public Boolean enabled;

    @Override
    public String toString() {
      return "Asm{" + "enabled=" + enabled + '}';
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
    return "AppSecFeatures{" + "asm=" + asm + ", autoUserInstrum=" + autoUserInstrum + '}';
  }
}
