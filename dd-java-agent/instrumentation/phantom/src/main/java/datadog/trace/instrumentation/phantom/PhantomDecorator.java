package datadog.trace.instrumentation.phantom;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.decorator.OrmClientDecorator;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;

public class PhantomDecorator extends OrmClientDecorator {
  public static final PhantomDecorator DECORATE = new PhantomDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"phantom"};
  }

  @Override
  protected String spanType() {
    return "phantom";
  }

  @Override
  protected String component() {
    return "scala-phantom";
  }

  @Override
  public String entityName(Object entity) {
    return null;
  }

  @Override
  protected String dbType() {
    return DDSpanTypes.CASSANDRA;
  }

  @Override
  protected String dbUser(Object o) {
    return null;
  }

  @Override
  protected String dbInstance(Object o) {
    return null;
  }

  @Override
  protected String service() {
    return "phantom";
  }
}
