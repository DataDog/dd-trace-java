package datadog.trace.core.jfr.openjdk;

@FunctionalInterface
public interface ThreadCpuTimeProvider {
  long getThreadCpuTime();
}
