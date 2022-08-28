package datadog.trace.instrumentation.hibernate;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;

import net.bytebuddy.matcher.ElementMatcher;

public final class HibernateMatchers {

  public static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassNamed("org.hibernate.Session");

  private HibernateMatchers() {}
}
