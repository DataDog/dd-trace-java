package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class DataSourceDecorator extends BaseDecorator {
  public static final CharSequence DATABASE_CONNECTION =
      UTF8BytesString.create("database.connection");
  public static final CharSequence JAVA_JDBC_CONNECTION =
      UTF8BytesString.create("java-jdbc-connection");

  public static final DataSourceDecorator DECORATE = new DataSourceDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jdbc-datasource"};
  }

  @Override
  protected CharSequence component() {
    return JAVA_JDBC_CONNECTION;
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }
}
