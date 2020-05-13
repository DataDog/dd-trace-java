package com.datadog.profiling.jfr;

/** A struct-like representation of a JFR annotation */
public final class Annotation {
  public static final String ANNOTATION_SUPER_TYPE_NAME = "java.lang.annotation.Annotation";
  public final Type type;
  public final String value;

  /**
   * Create a new {@linkplain Annotation} instance
   *
   * @param type the annotation type (must have the value of {@linkplain
   *     Annotation#ANNOTATION_SUPER_TYPE_NAME} as its super type)
   * @param value the annotation value or {@literal null}
   * @throws IllegalArgumentException if the annotation type is not having the value of {@linkplain
   *     Annotation#ANNOTATION_SUPER_TYPE_NAME} as its super type
   */
  public Annotation(Type type, String value) {
    if (!isAnnotationType(type)) {
      throw new IllegalArgumentException();
    }
    this.type = type;
    this.value = value;
  }

  public static boolean isAnnotationType(Type type) {
    return ANNOTATION_SUPER_TYPE_NAME.equals(type.getSupertype());
  }
}
