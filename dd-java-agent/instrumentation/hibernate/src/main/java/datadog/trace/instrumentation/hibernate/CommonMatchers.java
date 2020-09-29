package datadog.trace.instrumentation.hibernate;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;

import net.bytebuddy.matcher.ElementMatcher;

public final class CommonMatchers {

  public static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("org.hibernate.Session");

  private CommonMatchers() {}
}
