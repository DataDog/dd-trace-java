package datadog.trace.agent.tooling.csi;

import net.bytebuddy.jar.asm.Handle;

public interface CallSiteAdvice {

  interface MethodHandler {

    /** Executes an instruction without parameters */
    void instruction(int opcode);

    /** Executes an instruction with an int parameter */
    void instruction(final int opcode, final int parameter);

    /** Executes an instruction with a type parameter */
    void instruction(int opcode, String type);

    /** Loads a constant into the stack */
    void loadConstant(Object constant);

    /** Loads an array of constants into the stack as a reference of type <code>Object[]</code> */
    void loadConstantArray(Object[] array);

    /** Performs a field access invocation (static, special, virtual, interface...) */
    void field(int opcode, String owner, String field, String descriptor);

    /** Performs a method invocation (static, special, virtual, interface...) */
    void method(int opcode, String owner, String name, String descriptor, boolean isInterface);

    /** Performs an advice invocation (always static) */
    void advice(String owner, String name, String descriptor);

    /** Performs a dynamic method invocation */
    void invokeDynamic(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments);

    /** Duplicates all the method parameters in the stack just before the method is invoked. */
    void dupParameters(String methodDescriptor, StackDupMode mode);

    /**
     * Duplicates the specified method parameters in the stack just before the method is invoked.
     *
     * @param owner if this is an instance method (but the advice method doesn't have any parameter
     *     annotated with @This), then the owner of the method invocation. Otherwise <code>null
     *     </code>.
     */
    void dupParameters(String methodDescriptor, int[] indexes, String owner);

    /**
     * Duplicates the <code>this</code> reference and all the method parameters in the stack just
     * before the method is invoked (only for instance methods for obvious reasons).
     */
    void dupInvoke(String owner, String methodDescriptor, StackDupMode mode);

    /** Variant taking positional (partial or non-sequential) argument injection. */
    void dupInvoke(String owner, String methodDescriptor, int[] parameterIndices);
  }

  /** This enumeration describes how to duplicate the parameters in the stack */
  enum StackDupMode {
    /** Create a 1-1 copy of the parameters */
    COPY,
    /** Copies the parameters in an array and prepends it */
    PREPEND_ARRAY,
    /** Copies the parameters in an array and adds it between NEW and DUP opcodes */
    PREPEND_ARRAY_CTOR,
    /** Copies the parameters in an array and adds it before the uninitialized instance in a ctor */
    PREPEND_ARRAY_SUPER_CTOR,
    /** Copies the parameters in an array and appends it */
    APPEND_ARRAY
  }

  abstract class AdviceType {

    private AdviceType() {}

    public static final byte BEFORE = -1;
    public static final byte AROUND = 0;
    public static final byte AFTER = 1;
  }
}
