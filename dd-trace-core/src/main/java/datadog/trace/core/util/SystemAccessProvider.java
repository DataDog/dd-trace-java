package datadog.trace.core.util;

/**
 * A pluggable system provider used by {@linkplain SystemAccess}. {@linkplain SystemAccess} may not
 * use JMX classes (even via transitive dependencies) due to potential race in j.u.l initialization.
 * Therefore it uses an abstract {@linkplain SystemAccessProvider} type to hold the actual
 * implementation which may be switched between the {@linkplain SystemAccessProvider#NONE} and
 * {@linkplain JmxSystemAccessProvider} on-the-fly once JMX is safe to use.
 */
public interface SystemAccessProvider {
  SystemAccessProvider NONE = new NoneSystemAccessProvider();

  /** Get the current thread CPU time */
  long getThreadCpuTime();
}
