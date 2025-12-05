package datadog.trace.bootstrap.instrumentation.java.lang;

public final class VirtualThreadHelper {
  public static final String VIRTUAL_THREAD_CLASS_NAME = "java.lang.VirtualThread";

  /**
   * {@link datadog.trace.bootstrap.instrumentation.api.AgentScope} class name as string literal.
   * This is mandatory for {@link datadog.trace.bootstrap.ContextStore} API call.
   */
  public static final String AGENT_SCOPE_CLASS_NAME =
      "datadog.trace.bootstrap.instrumentation.api.AgentScope";
}
