package datadog.trace.agent.tooling.context;

import static datadog.config.util.Strings.getInternalName;
import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.FieldBackedContextStore;
import datadog.trace.bootstrap.FieldBackedContextStores;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites {@link InstrumentationContext} calls by allocating {@link ContextStore} ids during
 * transformation and using them to retrieve {@link ContextStore} instances at execution time.
 */
public final class FieldBackedContextRequestRewriter implements AsmVisitorWrapper {

  private static final Logger log =
      LoggerFactory.getLogger(FieldBackedContextRequestRewriter.class);

  static final String INSTRUMENTATION_CONTEXT_CLASS =
      getInternalName(InstrumentationContext.class.getName());

  static final String FIELD_BACKED_CONTEXT_STORES_CLASS =
      getInternalName(FieldBackedContextStores.class.getName());

  static final String GET_METHOD = "get";
  static final String GET_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(
          Type.getType(ContextStore.class), Type.getType(Class.class), Type.getType(Class.class));
  static final String GET_METHOD_DESCRIPTOR_2 =
      Type.getMethodDescriptor(
          Type.getType(ContextStore.class), Type.getType(String.class), Type.getType(String.class));

  static final String GET_CONTENT_STORE_METHOD = "getContextStore";
  static final String GET_CONTENT_STORE_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(Type.getType(FieldBackedContextStore.class), Type.INT_TYPE);

  static final String FIELD_BACKED_CONTENT_STORE_DESCRIPTOR =
      Type.getDescriptor(FieldBackedContextStore.class);

  static final String FAST_CONTENT_STORE_PREFIX = "contextStore";

  final Map<String, String> contextStore;
  final String instrumenterClassName;

  public FieldBackedContextRequestRewriter(
      final Map<String, String> contextStore, final String instrumenterClassName) {
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
    return new ClassVisitor(Opcodes.ASM8, classVisitor) {
      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(api, mv) {
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
                && (GET_METHOD_DESCRIPTOR.equals(descriptor)
                    || GET_METHOD_DESCRIPTOR_2.equals(descriptor))) {
              log.debug(
                  "Found context-store access - instrumentation.class={}", instrumenterClassName);
              // We track the last two constants pushed onto the stack to make sure they match
              // the expected key and context types. Matching calls are rewritten to call the
              // dynamically injected context store implementation instead.
              String keyClassName = null;
              String contextClassName = null;
              if (constant1 instanceof Type && constant2 instanceof Type) {
                keyClassName = ((Type) constant1).getClassName();
                contextClassName = ((Type) constant2).getClassName();
              } else if (constant1 instanceof String && constant2 instanceof String) {
                keyClassName = (String) constant1;
                contextClassName = (String) constant2;
              }
              if (null != keyClassName && null != contextClassName) {
                if (log.isDebugEnabled()) {
                  log.debug(
                      "Rewriting context-store map fetch - instrumentation.class={} instrumentation.target.context={}->{}",
                      instrumenterClassName,
                      keyClassName,
                      contextClassName);
                }
                if (!contextClassName.equals(contextStore.get(keyClassName))) {
                  throw new IllegalStateException(
                      String.format(
                          "Incorrect Context Api Usage detected. Incorrect context class %s, expected %s for instrumentation %s",
                          contextClassName, contextStore.get(keyClassName), instrumenterClassName));
                }
                // discard original parameters so we can use numeric id instead
                mv.visitInsn(Opcodes.POP2);

                int storeId = getContextStoreId(keyClassName, contextClassName);
                // use fast direct field access for a small number of stores
                if (storeId < FieldBackedContextStores.FAST_STORE_ID_LIMIT) {
                  mv.visitFieldInsn(
                      Opcodes.GETSTATIC,
                      FIELD_BACKED_CONTEXT_STORES_CLASS,
                      FAST_CONTENT_STORE_PREFIX + storeId,
                      FIELD_BACKED_CONTENT_STORE_DESCRIPTOR);
                } else {
                  mv.visitLdcInsn(storeId);
                  mv.visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      FIELD_BACKED_CONTEXT_STORES_CLASS,
                      GET_CONTENT_STORE_METHOD,
                      GET_CONTENT_STORE_METHOD_DESCRIPTOR,
                      false);
                }
              } else {
                throw new IllegalStateException(
                    "Incorrect Context Api Usage detected. Key and context class must be class-literals. "
                        + "Example of correct usage: InstrumentationContext.get(Runnable.class, RunnableContext.class)");
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
}
