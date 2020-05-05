package com.datadog.profiling.jfr;

public class JFRAnnotation {
  public static final String ANNOTATION_SUPER_TYPE_NAME = "java.lang.annotation.Annotation";
  public final JFRType type;
  public final String value;

  public JFRAnnotation(JFRType type, String value) {
    if (!type.getSupertype().equals(ANNOTATION_SUPER_TYPE_NAME)) {
      throw new IllegalArgumentException();
    }
    this.type = type;
    this.value = value;
  }
}
