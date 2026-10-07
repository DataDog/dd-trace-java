package datadog.trace.instrumentation.hibernate;

import datadog.trace.api.GenericClassValue;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.OrmClientDecorator;
import java.lang.annotation.Annotation;
import java.util.List;

public class HibernateDecorator extends OrmClientDecorator {
  public static final CharSequence HIBERNATE_SESSION = UTF8BytesString.create("hibernate.session");
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("hibernate");
  public static final HibernateDecorator DECORATOR = new HibernateDecorator();

  private static final ClassValue<String> ENTITY_NAMES =
      GenericClassValue.of(
          type -> {
            for (Annotation annotation : type.getDeclaredAnnotations()) {
              if ("javax.persistence.Entity".equals(annotation.annotationType().getName())) {
                return type.getName();
              }
            }
            return null;
          });

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"hibernate-core"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HIBERNATE;
  }

  @Override
  protected CharSequence component() {
    return "java-hibernate";
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
  protected String dbHostname(Object o) {
    return null;
  }

  @Override
  public String entityName(final Object entity) {
    if (entity == null) {
      return null;
    }
    if (entity instanceof String) {
      // We were given an entity name, not the entity itself.
      return (String) entity;
    }

    String name = ENTITY_NAMES.get(entity.getClass());
    if (name != null) {
      return name;
    }
    if (entity instanceof List) {
      List<?> entities = (List<?>) entity;
      if (!entities.isEmpty()) {
        return entityName(entities.get(0));
      }
    }
    return null;
  }
}
