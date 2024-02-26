package otel;

import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This visitor converts OpenTelemetry VirtualField API usage into Datadog ContextStore and InstrumentationContext usage.
 */
public class VirtualFieldConverter extends ClassVisitor {
  private static final Logger LOGGER = LoggerFactory.getLogger(VirtualFieldConverter.class);
  // VirtualField
  private static final String VIRTUAL_FIELD_CLASS = "io/opentelemetry/instrumentation/api/util/VirtualField";
  private static final String FIND_METHOD = "find";
  private static final String FIND_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.getType("L" + VIRTUAL_FIELD_CLASS + ";"), Type.getType(Class.class), Type.getType(Class.class));
  // ContextStore
  private static final String CONTEXT_STORE_CLASS = "datadog/trace/bootstrap/ContextStore";
  private static final String GET_METHOD = "get";
  private static final String SET_METHOD = "set";
  private static final String GET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object.class));
  private static final String SET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Object.class), Type.getType(Object.class));
  // InstrumentationContext
  private static final String INSTRUMENTATION_CONTEXT_CLASS = "datadog/trace/bootstrap/InstrumentationContext";
  private static final String INSTRUMENTATION_CONTEXT_GET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.getType("L" + CONTEXT_STORE_CLASS + ";"), Type.getType(Class.class), Type.getType(Class.class));
  /**
   * The visited class name.
   */
  private final String className;

  public VirtualFieldConverter(ClassVisitor classVisitor, String className) {
    super(ASM9, classVisitor);
    this.className = className;
  }

  @Override
  public MethodVisitor visitMethod(
      final int access,
      final String name,
      final String descriptor,
      final String signature,
      final String[] exceptions) {
    final MethodVisitor defaultVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
    return createMethodVisitor(defaultVisitor);
  }

  private MethodVisitor createMethodVisitor(final MethodVisitor defaultVisitor) {
    return new MethodVisitor(ASM9, defaultVisitor) {
      /* The last two constants pushed onto the stack. */
      private Object constant1;
      private Object constant2;

      @Override
      public void visitLdcInsn(final Object value) {
        // 'first' constant gets moved over once 'second' one comes in
        constant1 = constant2;
        constant2 = value;
        super.visitLdcInsn(value);
      }

      @Override
      public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
        // Look for call to VirtualField.find(T.class, F.class)
        if (INVOKESTATIC == opcode
            && VIRTUAL_FIELD_CLASS.equals(owner)
            && FIND_METHOD.equals(name)
            && FIND_METHOD_DESCRIPTOR.equals(descriptor)) {
          rewriteFind();
        }
        // Look for call to VirtualField.get(T.class)
        else if (INVOKEVIRTUAL == opcode
            && VIRTUAL_FIELD_CLASS.equals(owner)
            && GET_METHOD.equals(name)
            && GET_METHOD_DESCRIPTOR.equals(descriptor)) {
          rewriteGet();
        }
        // Look for call to VirtualField.set(T.class, F.class)
        else if (INVOKEVIRTUAL == opcode
            && VIRTUAL_FIELD_CLASS.equals(owner)
            && SET_METHOD.equals(name)
            && SET_METHOD_DESCRIPTOR.equals(descriptor)) {
          rewriteSet();
        }
        // Otherwise, copy method
        else {
          super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
        // Reset constants for next method check
        constant1 = null;
        constant2 = null;
      }

      private void rewriteFind() {
        LOGGER.debug("Found VirtualField.find() access - instrumentation.class={}", className);
        // We track the last two constants pushed onto the stack to make sure they match
        // the expected key and context types. Matching calls are rewritten to call the
        // dynamically injected context store implementation instead.
        if (!(constant1 instanceof Type) || !(constant2 instanceof Type)) {
          throw new IllegalStateException("Unexpected VirtualField.find() parameter types: " + constant1.getClass().getName() + " and " + constant2.getClass().getName());
        }
        String keyClassName = ((Type) constant1).getClassName();
        String contextClassName = ((Type) constant2).getClassName();
        LOGGER.debug(
            "Rewriting VirtualField.find() into InstrumentationContext.get() - instrumentation.class={} instrumentation.target.context={}->{}",
            className,
            keyClassName,
            contextClassName);
        mv.visitMethodInsn(
            INVOKESTATIC,
            INSTRUMENTATION_CONTEXT_CLASS,
            GET_METHOD,
            INSTRUMENTATION_CONTEXT_GET_METHOD_DESCRIPTOR,
            false);
      }

      private void rewriteGet() {
        LOGGER.debug(
            "Rewriting VirtualField.get() into ContextStore.get() - instrumentation.class={}", className);
        mv.visitMethodInsn(
            INVOKEINTERFACE,
            CONTEXT_STORE_CLASS,
            GET_METHOD,
            GET_METHOD_DESCRIPTOR,
            true);
      }

      private void rewriteSet() {
        LOGGER.debug(
            "Rewriting VirtualField.set() into ContextStore.set() - instrumentation.class={}", className);
        mv.visitMethodInsn(
            INVOKEINTERFACE,
            CONTEXT_STORE_CLASS,
            SET_METHOD,
            SET_METHOD_DESCRIPTOR,
            true);
      }
    };
  }
}
