package datadog.trace.instrumentation.sparkjava;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class SparkJavaDecorator extends BaseDecorator {

  public static final SparkJavaDecorator DECORATE = new SparkJavaDecorator();

  public static final CharSequence SPARK_JAVA = UTF8BytesString.create("spark-java");
  public static final CharSequence SPARK_REQUEST = UTF8BytesString.create("spark.request");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"sparkjava"};
  }

  @Override
  protected CharSequence spanType() {
    return "web";
  }

  @Override
  protected CharSequence component() {
    return SPARK_JAVA;
  }

  public CharSequence spanName() {
    return SPARK_REQUEST;
  }
}
