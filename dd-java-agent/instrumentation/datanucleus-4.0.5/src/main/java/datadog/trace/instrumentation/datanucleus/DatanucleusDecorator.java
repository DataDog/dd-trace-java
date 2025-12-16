package datadog.trace.instrumentation.datanucleus;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.OrmClientDecorator;
import org.datanucleus.identity.SCOID;
import org.datanucleus.identity.SingleFieldId;

public class DatanucleusDecorator extends OrmClientDecorator {

  public static final CharSequence DATANUCLEUS_FIND_OBJECT =
      UTF8BytesString.create("datanucleus.findObject");
  public static final CharSequence DATANUCLEUS_QUERY_EXECUTE =
      UTF8BytesString.create("datanucleus.query.execute");
  public static final CharSequence DATANUCLEUS_QUERY_DELETE =
      UTF8BytesString.create("datanucleus.query.delete");
  public static final CharSequence JAVA_DATANUCLEUS = UTF8BytesString.create("java-datanucleus");
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("datanucleus");
  public static final DatanucleusDecorator DECORATE = new DatanucleusDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"datanucleus"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.DATANUCLEUS;
  }

  @Override
  protected CharSequence component() {
    return JAVA_DATANUCLEUS;
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  public CharSequence entityName(final Object entity) {
    return className(entity.getClass());
  }

  public AgentSpan setResourceFromIdOrClass(AgentSpan span, Object id, String className) {
    String targetClass = null;
    if (className != null) {
      targetClass = className;
    } else if (id instanceof SingleFieldId) {
      targetClass = ((SingleFieldId) id).getTargetClassName();
    } else if (id instanceof SCOID) {
      targetClass = ((SCOID) id).getSCOClass();
    }

    if (targetClass != null && !targetClass.isEmpty()) {
      span.setResourceName(targetClass.substring(targetClass.lastIndexOf(".") + 1));
    }

    return span;
  }

  @Override
  protected String dbType() {
    return null;
  }

  @Override
  protected String dbUser(final Object o) {
    return null;
  }

  @Override
  protected String dbInstance(final Object o) {
    return null;
  }

  @Override
  protected CharSequence dbHostname(Object o) {
    return null;
  }
}
