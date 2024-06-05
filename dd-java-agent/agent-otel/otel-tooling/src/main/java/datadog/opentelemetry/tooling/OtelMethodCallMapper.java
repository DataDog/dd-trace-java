package datadog.opentelemetry.tooling;

import static datadog.trace.bootstrap.FieldBackedContextStores.FAST_STORE_ID_LIMIT;
import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;

import datadog.trace.bootstrap.FieldBackedContextStore;
import datadog.trace.bootstrap.FieldBackedContextStores;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.jar.asm.commons.MethodRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/** Maps OpenTelemetry method calls to use the Datadog equivalent API. */
public final class OtelMethodCallMapper extends MethodRemapper {

  private static final String VIRTUAL_FIELD_CLASS =
      "io/opentelemetry/javaagent/shaded/instrumentation/api/util/VirtualField";

  private static final String FIELD_BACKED_CONTEXT_STORES_CLASS =
      Type.getInternalName(FieldBackedContextStores.class);

  private static final String GET_CONTENT_STORE_METHOD = "getContextStore";
  private static final String GET_CONTENT_STORE_METHOD_DESCRIPTOR =
      Type.getMethodDescriptor(Type.getType(FieldBackedContextStore.class), Type.INT_TYPE);

  private static final String FIELD_BACKED_CONTENT_STORE_DESCRIPTOR =
      Type.getDescriptor(FieldBackedContextStore.class);

  private static final String FAST_CONTENT_STORE_PREFIX = "contextStore";

  /** The last two constants pushed onto the stack. */
  private Object constant1, constant2;

  public OtelMethodCallMapper(MethodVisitor methodVisitor, Remapper remapper) {
    super(methodVisitor, remapper);
  }

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

    if (VIRTUAL_FIELD_CLASS.equals(owner)) {
      // replace VirtualField calls with their equivalent in the ContextStore API
      if ("find".equals(name)) {
        redirectVirtualFieldLookup();
        return;
      } else if ("set".equals(name)) {
        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "put", descriptor, true);
        return;
      } else if ("get".equals(name)) {
        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "get", descriptor, true);
        return;
      }
    }

    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

    // reset constants for next method check
    constant1 = null;
    constant2 = null;
  }

  private void redirectVirtualFieldLookup() {
    // We track the last two constants pushed onto the stack to make sure they match
    // the expected key and context types. Matching calls are rewritten to call the
    // dynamically injected context store implementation instead.
    String keyClassName = null;
    String contextClassName = null;
    if (constant1 instanceof Type && constant2 instanceof Type) {
      keyClassName = ((Type) constant1).getClassName();
      contextClassName = ((Type) constant2).getClassName();
    }

    if (null == keyClassName || null == contextClassName) {
      throw new IllegalStateException(
          "Incorrect VirtualField usage detected. Type and fieldType must be class-literals. "
              + "Example of correct usage: VirtualField.find(Runnable.class, RunnableContext.class)");
    }

    // discard original parameters so we can use numeric id instead
    mv.visitInsn(Opcodes.POP2);

    int storeId = getContextStoreId(keyClassName, contextClassName);
    // use fast direct field access for a small number of stores
    if (storeId < FAST_STORE_ID_LIMIT) {
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
  }
}
