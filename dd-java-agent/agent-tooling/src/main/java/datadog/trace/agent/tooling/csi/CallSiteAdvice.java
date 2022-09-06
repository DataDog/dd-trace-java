package datadog.trace.agent.tooling.csi;

import net.bytebuddy.jar.asm.MethodVisitor;

/**
 * Interface to implement by call site advices, the {@link CallSiteAdvice#apply(MethodVisitor, int,
 * String, String, String, boolean)} method will be used by ByteBuddy to perform the actual
 * instrumentation.
 */
public interface CallSiteAdvice {

  Pointcut pointcut();

  void apply(
      MethodVisitor mv,
      int opcode,
      String owner,
      String name,
      String descriptor,
      boolean isInterface);

  interface HasHelpers {
    String[] helperClassNames();
  }
}
