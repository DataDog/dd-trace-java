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
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

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
          /** The most recent objects pushed to the stack. */
          private final Object[] stack = {null, null};
          /** Most recent instructions. */
          private final int[] insnStack = {-1, -1, -1};

          @Override
          public void visitMethodInsn(
              final int opcode,
              final String owner,
              final String name,
              final String descriptor,
              final boolean isInterface) {
            pushOpcode(opcode);
            if (INSTRUMENTATION_CONTEXT_CLASS.equals(owner)
                && GET_METHOD.equals(name)
                && GET_METHOD_DESCRIPTOR.equals(descriptor)) {
              log.debug("Found context-store access in {}", instrumenterClassName);
              /*
              The idea here is that the rest if this method visitor collects last three instructions in `insnStack`
              variable. Once we get here we check if those last three instructions constitute call that looks like
              `InstrumentationContext.get(K.class, V.class)`. If it does the inside of this if rewrites it to call
              dynamically injected context store implementation instead.
               */
              if ((insnStack[0] == Opcodes.INVOKESTATIC
                      && insnStack[1] == Opcodes.LDC
                      && insnStack[2] == Opcodes.LDC)
                  && (stack[0] instanceof Type && stack[1] instanceof Type)) {
                final String contextClassName = ((Type) stack[0]).getClassName();
                final String keyClassName = ((Type) stack[1]).getClassName();
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
                return;
              }
              throw new IllegalStateException(
                  "Incorrect Context Api Usage detected. Key and context class must be class-literals. Example of correct usage: InstrumentationContext.get(Runnable.class, RunnableContext.class)");
            } else {
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
          }

          /** Tracking the most recently used opcodes to assert proper api usage. */
          private void pushOpcode(final int opcode) {
            System.arraycopy(insnStack, 0, insnStack, 1, insnStack.length - 1);
            insnStack[0] = opcode;
          }

          /** Tracking the most recently pushed objects on the stack to assert proper api usage. */
          private void pushStack(final Object o) {
            System.arraycopy(stack, 0, stack, 1, stack.length - 1);
            stack[0] = o;
          }

          @Override
          public void visitInsn(final int opcode) {
            pushOpcode(opcode);
            super.visitInsn(opcode);
          }

          @Override
          public void visitJumpInsn(final int opcode, final Label label) {
            pushOpcode(opcode);
            super.visitJumpInsn(opcode, label);
          }

          @Override
          public void visitIntInsn(final int opcode, final int operand) {
            pushOpcode(opcode);
            super.visitIntInsn(opcode, operand);
          }

          @Override
          public void visitVarInsn(final int opcode, final int var) {
            pushOpcode(opcode);
            pushStack(var);
            super.visitVarInsn(opcode, var);
          }

          @Override
          public void visitLdcInsn(final Object value) {
            pushOpcode(Opcodes.LDC);
            pushStack(value);
            super.visitLdcInsn(value);
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
