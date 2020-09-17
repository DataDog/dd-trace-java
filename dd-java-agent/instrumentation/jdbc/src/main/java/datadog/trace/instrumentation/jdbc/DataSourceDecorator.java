package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class DataSourceDecorator extends BaseDecorator {
  public static final CharSequence DATABASE_CONNECTION =
      UTF8BytesString.createConstant("database.connection");

  public static final DataSourceDecorator DECORATE = new DataSourceDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jdbc-datasource"};
  }

  @Override
  protected String component() {
    return "java-jdbc-connection";
  }

  @Override
  protected String spanType() {
    return null;
  }
}
