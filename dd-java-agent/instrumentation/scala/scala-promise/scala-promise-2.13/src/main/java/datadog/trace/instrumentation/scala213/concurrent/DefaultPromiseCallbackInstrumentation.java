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
        return new UnregisterCallbackMethodVisitor(methodVisitor, contextStoreId);
      }
      return methodVisitor;
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

    UnregisterCallbackMethodVisitor(MethodVisitor methodVisitor, int contextStoreId) {
      super(Opcodes.ASM9, methodVisitor);
      this.contextStoreId = contextStoreId;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (rewrittenCallSites < 2
          && opcode == Opcodes.INVOKEVIRTUAL
          && DEFAULT_PROMISE.equals(owner)
          && "compareAndSet".equals(name)
          && COMPARE_AND_SET_DESCRIPTOR.equals(descriptor)) {
        rewrittenCallSites++;
        replaceCompareAndSet();
      } else {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
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
