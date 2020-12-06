package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.context.ContextStoreUtils.getContextStoreImplementationClassName;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

/** @deprecated not used in the new field-injection strategy */
@Deprecated
@Slf4j
final class ContextStoreReadsRewritingVisitor implements AsmVisitorWrapper {

  private static final String INSTRUMENTATION_CONTEXT_CLASS =
      Utils.getInternalName(InstrumentationContext.class.getName());

  private static final String GET_METHOD = "get";
  private static final String GET_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(
          Type.getType(ContextStore.class), Type.getType(Class.class), Type.getType(Class.class));

  private static final String GET_CONTENT_STORE_METHOD = "getContextStore";
  private static final String GET_CONTENT_STORE_METHOD_DESCRIPTOR =
      GET_METHOD_DESCRIPTOR; // same signature as `InstrumentationContext.get` method

  /** context-store-type-name -> context-store-type-name-dynamic-type */
  private final Map<String, DynamicType.Unloaded<?>> contextStoreImplementations;

  private final Map<String, String> contextStore;
  private final String instrumenterClassName;

  public ContextStoreReadsRewritingVisitor(
      Map<String, DynamicType.Unloaded<?>> contextStoreImplementations,
      Map<String, String> contextStore,
      String instrumenterClassName) {
    this.contextStoreImplementations = contextStoreImplementations;
    this.contextStore = contextStore;
    this.instrumenterClassName = instrumenterClassName;
  }

  @Override
  public int mergeWriter(final int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(final int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      final TypeDescription instrumentedType,
      final ClassVisitor classVisitor,
      final Implementation.Context implementationContext,
      final TypePool typePool,
      final FieldList<FieldDescription.InDefinedShape> fields,
      final MethodList<?> methods,
      final int writerFlags,
      final int readerFlags) {
    return new ClassVisitor(Opcodes.ASM7, classVisitor) {
      @Override
      public void visit(
          final int version,
          final int access,
          final String name,
          final String signature,
          final String superName,
          final String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
      }

      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM7, mv) {
          /** The last two constants pushed onto the stack. */
          private Object constant1, constant2;

          @Override
          public void visitLdcInsn(final Object value) {
            // 'first' constant gets moved over once 'second' one comes in
            constant1 = constant2;
            constant2 = value;
            super.visitLdcInsn(value);
          }

          @Override
          public void visitMethodInsn(
              final int opcode,
              final String owner,
              final String name,
              final String descriptor,
              final boolean isInterface) {
            // Look for any calls to `InstrumentationContext.get(K.class, C.class)`
            if (Opcodes.INVOKESTATIC == opcode
                && INSTRUMENTATION_CONTEXT_CLASS.equals(owner)
                && GET_METHOD.equals(name)
                && GET_METHOD_DESCRIPTOR.equals(descriptor)) {
              log.debug("Found context-store access in {}", instrumenterClassName);
              // We track the last two constants pushed onto the stack to make sure they match
              // the expected key and context types. Matching calls are rewritten to call the
              // dynamically injected context store implementation instead.
              if (constant1 instanceof Type && constant2 instanceof Type) {
                final String keyClassName = ((Type) constant1).getClassName();
                final String contextClassName = ((Type) constant2).getClassName();
                final TypeDescription contextStoreImplementationClass =
                    getContextStoreImplementation(keyClassName, contextClassName);
                if (log.isDebugEnabled()) {
                  log.debug(
                      "Rewriting context-store map fetch for instrumenter {}: {} -> {}",
                      instrumenterClassName,
                      keyClassName,
                      contextClassName);
                }
                if (contextStoreImplementationClass == null) {
                  throw new IllegalStateException(
                      String.format(
                          "Incorrect Context Api Usage detected. Cannot find map holder class for %s context %s. Was that class defined in contextStore for instrumentation %s?",
                          keyClassName, contextClassName, instrumenterClassName));
                }
                if (!contextClassName.equals(contextStore.get(keyClassName))) {
                  throw new IllegalStateException(
                      String.format(
                          "Incorrect Context Api Usage detected. Incorrect context class %s, expected %s for instrumentation %s",
                          contextClassName, contextStore.get(keyClassName), instrumenterClassName));
                }
                // stack: contextClass | keyClass
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    contextStoreImplementationClass.getInternalName(),
                    GET_CONTENT_STORE_METHOD,
                    GET_CONTENT_STORE_METHOD_DESCRIPTOR,
                    false);
              } else {
                throw new IllegalStateException(
                    "Incorrect Context Api Usage detected. Key and context class must be class-literals. Example of correct usage: InstrumentationContext.get(Runnable.class, RunnableContext.class)");
              }
            } else {
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            // reset constants for next method check
            constant1 = null;
            constant2 = null;
          }
        };
      }
    };
  }

  private TypeDescription getContextStoreImplementation(
      final String keyClassName, final String contextClassName) {
    final DynamicType.Unloaded<?> type =
        contextStoreImplementations.get(
            getContextStoreImplementationClassName(keyClassName, contextClassName));
    if (type == null) {
      return null;
    } else {
      return type.getTypeDescription();
    }
  }
}
