package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.scala.ScalaPromiseContinuationHelper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

@AutoService(InstrumenterModule.class)
public final class DefaultPromiseCallbackInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasTypeAdvice {

  private static final String TRANSFORMATION = "scala.concurrent.impl.Promise$Transformation";

  public DefaultPromiseCallbackInstrumentation() {
    super("scala_concurrent");
  }

  @Override
  public String muzzleDirective() {
    return "scala-promise-unregister";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(TRANSFORMATION, State.class.getName());
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("scala.concurrent.impl.Promise$DefaultPromise")
          .withMethod(
              new String[0],
              EXPECTS_NON_STATIC,
              "unregisterCallback",
              "V",
              "Lscala/concurrent/impl/Promise$Transformation;")
          .build()
    };
  }

  @Override
  public String instrumentedType() {
    return "scala.concurrent.impl.Promise$DefaultPromise";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(
        new UnregisterCallbackVisitorWrapper(
            getContextStoreId(TRANSFORMATION, State.class.getName())));
  }

  public static final class UnregisterCallbackVisitorWrapper
      extends AsmVisitorWrapper.AbstractBase {
    private final int contextStoreId;

    public UnregisterCallbackVisitorWrapper(int contextStoreId) {
      this.contextStoreId = contextStoreId;
    }

    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      return new UnregisterCallbackClassVisitor(classVisitor, contextStoreId);
    }
  }

  static final class UnregisterCallbackClassVisitor extends ClassVisitor {
    private static final String UNREGISTER_DESCRIPTOR =
        "(Lscala/concurrent/impl/Promise$Transformation;)V";

    private final int contextStoreId;
    private boolean foundUnregisterCallback;

    UnregisterCallbackClassVisitor(ClassVisitor classVisitor, int contextStoreId) {
      super(Opcodes.ASM9, classVisitor);
      this.contextStoreId = contextStoreId;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor methodVisitor =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      if ("unregisterCallback".equals(name) && UNREGISTER_DESCRIPTOR.equals(descriptor)) {
        if ((access & Opcodes.ACC_STATIC) != 0) {
          throw new IllegalStateException("Expected an instance unregisterCallback method");
        }
        foundUnregisterCallback = true;
        return new UnregisterCallbackMethodVisitor(methodVisitor, contextStoreId);
      }
      return methodVisitor;
    }

    @Override
    public void visitEnd() {
      if (!foundUnregisterCallback) {
        throw new IllegalStateException("Missing expected unregisterCallback method");
      }
      super.visitEnd();
    }
  }

  static final class UnregisterCallbackMethodVisitor extends MethodVisitor {
    private static final String DEFAULT_PROMISE = "scala/concurrent/impl/Promise$DefaultPromise";
    private static final String COMPARE_AND_SET_DESCRIPTOR =
        "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String CONTINUATION_HELPER =
        Type.getInternalName(ScalaPromiseContinuationHelper.class);
    private static final String CANCEL_DESCRIPTOR =
        Type.getMethodDescriptor(
            Type.BOOLEAN_TYPE,
            Type.getType(AtomicReference.class),
            Type.getType(Object.class),
            Type.getType(Object.class),
            Type.INT_TYPE,
            Type.getType(Object.class));

    private final int contextStoreId;
    private int rewrittenCallSites;
    private boolean loadedCallback;

    UnregisterCallbackMethodVisitor(MethodVisitor methodVisitor, int contextStoreId) {
      super(Opcodes.ASM9, methodVisitor);
      this.contextStoreId = contextStoreId;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      loadedCallback = false;
      if (opcode == Opcodes.INVOKEVIRTUAL
          && DEFAULT_PROMISE.equals(owner)
          && "compareAndSet".equals(name)
          && COMPARE_AND_SET_DESCRIPTOR.equals(descriptor)) {
        rewrittenCallSites++;
        replaceCompareAndSet();
      } else {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
      boolean writesCallback =
          (var == 1 && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE)
              || (var == 0 && (opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE));
      if (writesCallback && !(opcode == Opcodes.ASTORE && var == 1 && loadedCallback)) {
        throw new IllegalStateException("unregisterCallback overwrites its callback argument");
      }
      loadedCallback = opcode == Opcodes.ALOAD && var == 1;
      super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitLabel(Label label) {
      // Only allow adjacent ALOAD 1 / ASTORE 1, with no entry point into the store.
      loadedCallback = false;
      super.visitLabel(label);
    }

    @Override
    public void visitInsn(int opcode) {
      loadedCallback = false;
      super.visitInsn(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
      loadedCallback = false;
      super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      loadedCallback = false;
      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      loadedCallback = false;
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name, String descriptor, Handle bootstrapMethod, Object... bootstrapArguments) {
      loadedCallback = false;
      super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethod, bootstrapArguments);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      loadedCallback = false;
      super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLdcInsn(Object value) {
      loadedCallback = false;
      super.visitLdcInsn(value);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
      if (var == 1) {
        throw new IllegalStateException("unregisterCallback overwrites its callback argument");
      }
      loadedCallback = false;
      super.visitIincInsn(var, increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label defaultLabel, Label... labels) {
      loadedCallback = false;
      super.visitTableSwitchInsn(min, max, defaultLabel, labels);
    }

    @Override
    public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) {
      loadedCallback = false;
      super.visitLookupSwitchInsn(defaultLabel, keys, labels);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
      loadedCallback = false;
      super.visitMultiANewArrayInsn(descriptor, dimensions);
    }

    @Override
    public void visitEnd() {
      if (rewrittenCallSites != 2) {
        // Reject the combined class transformation instead of installing a partial rewrite.
        throw new IllegalStateException(
            "Expected 2 unregisterCallback compareAndSet sites, found " + rewrittenCallSites);
      }
      super.visitEnd();
    }

    private void replaceCompareAndSet() {
      super.visitLdcInsn(contextStoreId);
      super.visitVarInsn(Opcodes.ALOAD, 1);
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          CONTINUATION_HELPER,
          "compareAndSetAndCancel",
          CANCEL_DESCRIPTOR,
          false);
    }
  }
}
