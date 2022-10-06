package datadog.trace.agent.tooling.csi;

import net.bytebuddy.jar.asm.Handle;

/**
 * Interface to implement by call site advices for invoke dynamic, the {@link
 * InvokeDynamicAdvice#apply(MethodHandler, String, String, Handle, Object...)} method will be used
 * to perform the actual instrumentation.
 */
public interface InvokeDynamicAdvice extends CallSiteAdvice {

  void apply(
      MethodHandler handler,
      String name,
      String descriptor,
      Handle bootstrapMethodHandle,
      Object... bootstrapMethodArguments);
}
