package datadog.trace.bootstrap.instrumentation.java.lang;

/** This class is a helper for the java-lang-21.0 {@code VirtualThreadInstrumentation}. */
public final class VirtualThreadHelper {
  public static final String VIRTUAL_THREAD_CLASS_NAME = "java.lang.VirtualThread";

  /**
   * {@link VirtualThreadState} class name as string literal. This is mandatory for {@link
   * datadog.trace.bootstrap.ContextStore} API call.
   */
  public static final String VIRTUAL_THREAD_STATE_CLASS_NAME =
      "datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState";
}
