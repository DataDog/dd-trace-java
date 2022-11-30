package datadog.trace.agent.tooling.csi;

/**
 * Interface to implement by call site advices, the {@link InvokeAdvice#apply(MethodHandler, int,
 * String, String, String, boolean)} method will be used to perform the actual instrumentation.
 */
public interface InvokeAdvice extends CallSiteAdvice {

  void apply(
      MethodHandler handler,
      int opcode,
      String owner,
      String name,
      String descriptor,
      boolean isInterface);
}
