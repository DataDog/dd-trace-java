package com.datadog.featureflag.ufc.v1;

public class Variant {
  public final String key;
  public final Object value;

  public Variant(String key, Object value) {
    this.key = key;
    this.value = value;
  }
}
