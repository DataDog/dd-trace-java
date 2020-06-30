package datadog.trace.agent.tooling;

/**
 * Some useful constants.
 *
 * <p>Idea here is to keep this class safe to inject into client's class loader.
 */
public final class Constants {

  /**
   * packages which will be loaded on the bootstrap classloader
   *
   * <p>Updates should be mirrored in
   * datadog.trace.agent.test.SpockRunner#BOOTSTRAP_PACKAGE_PREFIXES_COPY
   */
  public static final String[] BOOTSTRAP_PACKAGE_PREFIXES = {
    "datadog.common.exec",
    "datadog.slf4j",
    "datadog.trace.api",
    "datadog.trace.bootstrap",
    "datadog.trace.context",
    "datadog.trace.instrumentation.api",
  };

  // This is used in IntegrationTestUtils.java
  public static final String[] AGENT_PACKAGE_PREFIXES = {
    "datadog.common.exec",
    "datadog.trace.common",
    "datadog.trace.agent",
    "datadog.trace.instrumentation",
    // guava
    "com.google.auto",
    "com.google.common",
    "com.google.thirdparty.publicsuffix",
    // WeakConcurrentMap
    "com.blogspot.mydailyjava.weaklockfree",
    // bytebuddy
    "net.bytebuddy",
    // msgpack
    "org.msgpack",
    // disruptor
    "com.lmax.disruptor",
    // okHttp
    "okhttp3",
    "okio",
    "jnr",
    "org.objectweb.asm",
    "com.kenai",
    // Custom RxJava Utility
    "rx.DDTracingUtil",
  };

  private Constants() {}
}
