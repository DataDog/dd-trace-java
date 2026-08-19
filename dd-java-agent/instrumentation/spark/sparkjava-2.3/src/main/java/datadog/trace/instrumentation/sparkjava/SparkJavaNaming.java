package datadog.trace.instrumentation.sparkjava;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public final class SparkJavaNaming {

  public static final CharSequence SPARK_JAVA = UTF8BytesString.create("spark-java");
  public static final CharSequence SPARK_REQUEST = UTF8BytesString.create("spark.request");

  private SparkJavaNaming() {}
}
