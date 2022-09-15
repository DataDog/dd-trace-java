package datadog.trace.agent.tooling.csi;

import net.bytebuddy.jar.asm.Handle;

public interface CallSiteAdvice {

  Pointcut pointcut();

  interface HasHelpers {
    String[] helperClassNames();
  }

  interface HasFlags {
    int COMPUTE_MAX_STACK = 1;

    int flags();
  }

  /** Interface to isolate advices from ASM */
  interface MethodHandler {

    /** Executes an instruction without parameters */
    void instruction(int opcode);

    /** Loads a constant into the stack */
    void loadConstant(Object constant);

    /** Loads an array of constants into the stack as a reference of type <code>Object[]</code> */
    void loadConstantArray(Object[] array);

    /** Performs a method invocation (static, special, virtual, interface...) */
    void method(int opcode, String owner, String name, String descriptor, boolean isInterface);

    /** Performs a dynamic method invocation */
    void invokeDynamic(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments);

    /** Duplicates all the method parameters in the stack just before the method is invoked. */
    void dupParameters(String methodDescriptor, StackDupMode mode);

    /**
     * Duplicates the <code>this</code> reference and all the method parameters in the stack just
     * before the method is invoked (only for instance methods for obvious reasons).
     */
    void dupInvoke(String owner, String methodDescriptor, StackDupMode mode);
  }

  /** This enumeration describes how to duplicate the parameters in the stack */
  enum StackDupMode {
    /** Create a 1-1 copy of the parameters */
    COPY,
    /** Copies the parameters in an array and prepends it */
    PREPEND_ARRAY,
    /** Copies the parameters in an array and appends it */
    APPEND_ARRAY
  }
}
