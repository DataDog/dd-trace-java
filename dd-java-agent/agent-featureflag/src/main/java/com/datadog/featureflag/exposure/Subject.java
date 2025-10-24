package com.datadog.featureflag.exposure;

public class Subject {
  public final String id;
  public final String type;
  public final Object attributes;

  public Subject(final String id, final String type, final Object attributes) {
    this.id = id;
    this.type = type;
    this.attributes = attributes;
  }
}
