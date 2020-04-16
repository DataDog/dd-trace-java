package datadog.trace.common.util;

/**
 * A pluggable thread CPU time provider used by {@linkplain ThreadCpuTimeAccess}. {@linkplain
 * ThreadCpuTimeAccess} may not use JMX classes (even via transitive dependencies) due to potential
 * race in j.u.l initialization. Therefore it uses an abstract {@linkplain ThreadCpuTimeProvider}
 * type to hold the actual implementation which may be switched between the {@linkplain
 * ThreadCpuTimeProvider#DEFAULT} and {@linkplain JmxThreadCpuTimeProvider} on-the-fly once JMX is
 * safe to use.
 */
abstract class ThreadCpuTimeProvider {
  static final ThreadCpuTimeProvider DEFAULT = new ThreadCpuTimeProvider() {};

  /**
   * Get the current thread CPU time
   *
   * @return {@linkplain Long#MIN_VALUE}
   */
  long getThreadCpuTime() {
    return Long.MIN_VALUE;
  }
}
