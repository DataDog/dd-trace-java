package datadog.trace.api.profiling;

public class Profiler {
  private static SessionFactory factory;

  public static Session startProfiling() {
    return factory.createSession();
  }

  public static void initialize(SessionFactory sessionFactory) {
    factory = sessionFactory;
  }
}
