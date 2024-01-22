package otel

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.slf4j.LoggerFactory

import static org.objectweb.asm.Opcodes.ASM9
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE
import static org.objectweb.asm.Opcodes.INVOKESTATIC
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL

class VirtualFieldConverter extends ClassVisitor {
  private static final LOGGER = LoggerFactory.getLogger(VirtualFieldConverter.class)
  // VirtualField
  private static final VIRTUAL_FIELD_CLASS = 'io/opentelemetry/instrumentation/api/util/VirtualField'
  private static final FIND_METHOD = 'find'
  private static final FIND_METHOD_DESCRIPTOR = Type.getMethodDescriptor(
    Type.getType("L${VIRTUAL_FIELD_CLASS};"), Type.getType(Class.class), Type.getType(Class.class))
  // InstrumentationContext
  private static final INSTRUMENTATION_CONTEXT_CLASS = 'datadog/trace/bootstrap/InstrumentationContext'
  private static final INSTRUMENTATION_CONTEXT_GET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(
    Type.getType("L${CONTEXT_STORE_CLASS};"), Type.getType(Class.class), Type.getType(Class.class))
  // ContextStore
  private static final CONTEXT_STORE_CLASS = 'datadog/trace/bootstrap/ContextStore'
  private static final GET_METHOD = 'get'
  private static final SET_METHOD = 'set'
  private static final GET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(
    Type.getType(Object.class), Type.getType(Object.class))
  private static final SET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(
    Type.VOID_TYPE, Type.getType(Object.class), Type.getType(Object.class))
  /** The visited class name. */
  private final String className

  VirtualFieldConverter(ClassVisitor classVisitor, String className) {
    super(ASM9, classVisitor) // TODO I supposed I could use the latest version
    this.className = className
  }

  @Override
  MethodVisitor visitMethod(
    final int access,
    final String name,
    final String descriptor,
    final String signature,
    final String[] exceptions) {
    final MethodVisitor defaultVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
    return createMethodVisitor(defaultVisitor)
  }

  private MethodVisitor createMethodVisitor(MethodVisitor defaultVisitor) {
    return new MethodVisitor(ASM9, defaultVisitor) {
      /** The last two constants pushed onto the stack. */
      private Object constant1, constant2

      @Override
      void visitLdcInsn(final Object value) {
        // 'first' constant gets moved over once 'second' one comes in
        constant1 = constant2
        constant2 = value
        super.visitLdcInsn(value)
      }

      @Override
      void visitMethodInsn(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
        // Look for any calls to `VirtualField.find(T.class, F.class)`
        if (VIRTUAL_FIELD_CLASS == owner) {
          println className
          println "$opcode | $name | $descriptor | $isInterface"
        }
        if (INVOKESTATIC == opcode
          && VIRTUAL_FIELD_CLASS == owner
          && FIND_METHOD == name
          && FIND_METHOD_DESCRIPTOR == descriptor) {
          rewriteFind()
        } else if (INVOKEVIRTUAL == opcode
          && VIRTUAL_FIELD_CLASS == owner
          && GET_METHOD == name
          && GET_METHOD_DESCRIPTOR == descriptor) {
          rewriteGet()
        } else if (INVOKEVIRTUAL == opcode
          && VIRTUAL_FIELD_CLASS == owner
          && SET_METHOD
          && SET_METHOD_DESCRIPTOR == descriptor) {
          rewriteSet()
        } else {
          super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        // reset constants for next method check
        constant1 = null
        constant2 = null
      }

      private void rewriteFind() {
        LOGGER.warn(
          "Found VirtualField.find() access - instrumentation.class={}", className)
        // We track the last two constants pushed onto the stack to make sure they match
        // the expected key and context types. Matching calls are rewritten to call the
        // dynamically injected context store implementation instead.
        if (!constant1 instanceof Type || !constant2 instanceof Type) {
          throw new IllegalStateException(
            "Unexpected VirtualField.find() parameter types: ${constant1.class.name} and ${constant2.class.name}")
        }
        String keyClassName = ((Type) constant1).getClassName()
        String contextClassName = ((Type) constant2).getClassName()
        LOGGER.debug(
          "Rewriting VirtualField.find() into InstrumentationContext.get() - instrumentation.class={} instrumentation.target.context={}->{}",
          className,
          keyClassName,
          contextClassName)
        mv.visitMethodInsn(
          INVOKESTATIC,
          INSTRUMENTATION_CONTEXT_CLASS,
          GET_METHOD,
          INSTRUMENTATION_CONTEXT_GET_METHOD_DESCRIPTOR,
          false)
      }

      private void rewriteGet() {
        LOGGER.debug(
          "Rewriting VirtualField.get() into ContextStore.get() - instrumentation.class={}", className)
        mv.visitMethodInsn(
          INVOKEINTERFACE,
          CONTEXT_STORE_CLASS,
          GET_METHOD,
          GET_METHOD_DESCRIPTOR,
          true)
      }

      private void rewriteSet() {
        LOGGER.debug(
          "Rewriting VirtualField.set() into ContextStore.set() - instrumentation.class={}", className)
        mv.visitMethodInsn(
          INVOKEINTERFACE,
          CONTEXT_STORE_CLASS,
          SET_METHOD,
          SET_METHOD_DESCRIPTOR,
          true)
      }
    }
  }
}
