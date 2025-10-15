package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class PoolWaitingDecorator extends BaseDecorator {
  public static final CharSequence POOL_WAITING = UTF8BytesString.create("pool.waiting");
  public static final CharSequence JAVA_JDBC_POOL_WAITING =
      UTF8BytesString.create("java-jdbc-pool-waiting");

  public static final PoolWaitingDecorator DECORATE = new PoolWaitingDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jdbc"};
  }

  @Override
  protected CharSequence component() {
    return JAVA_JDBC_POOL_WAITING;
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }
}
