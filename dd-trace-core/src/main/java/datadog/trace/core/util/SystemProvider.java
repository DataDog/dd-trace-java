package datadog.trace.core.util;

/**
 * A pluggable system provider used by {@linkplain SystemAccess}. {@linkplain SystemAccess} may not
 * use JMX classes (even via transitive dependencies) due to potential race in j.u.l initialization.
 * Therefore it uses an abstract {@linkplain SystemProvider} type to hold the actual implementation
 * which may be switched between the {@linkplain SystemProvider#NONE} and {@linkplain
 * JmxSystemProvider} on-the-fly once JMX is safe to use.
 */
public interface SystemProvider {
  SystemProvider NONE = new NoneSystemProvider();

  /** Get the current thread CPU time */
  long getThreadCpuTime();

  /** get the current pid */
  int getCurrentPid();
}
