package datadog.trace.agent.tooling.matchercache;

public interface ClassMatchers {
  boolean matchesAny(Class<?> cl);

  boolean isGloballyIgnored(String fullClassName);
}
