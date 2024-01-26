package datadog.trace.agent.tooling.bytebuddy.csi;

import static net.bytebuddy.jar.asm.ClassWriter.COMPUTE_MAXS;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode;
import datadog.trace.agent.tooling.csi.InvokeAdvice;
import datadog.trace.agent.tooling.csi.InvokeDynamicAdvice;
import java.security.ProtectionDomain;
import javax.annotation.Nonnull;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

public class CallSiteTransformer implements Instrumenter.TransformingAdvice {

  private static final Instrumenter.TransformingAdvice NO_OP =
      (builder, typeDescription, classLoader, module, pd) -> builder;

  public static final int ASM_API = Opcodes.ASM8;

  private final Advices advices;

  private final Instrumenter.TransformingAdvice helperInjector;

  public CallSiteTransformer(@Nonnull final Advices advices) {
    this("call-site-transformer", advices);
  }

  public CallSiteTransformer(@Nonnull final String name, @Nonnull final Advices advices) {
    this.advices = advices;
    final String[] helpers = advices.getHelpers();
    this.helperInjector =
        helpers == null || helpers.length == 0
            ? NO_OP
            : new HelperInjector(name, advices.getHelpers());
  }

  @Override
  public @Nonnull DynamicType.Builder<?> transform(
      @Nonnull final DynamicType.Builder<?> builder,
      @Nonnull final TypeDescription type,
      final ClassLoader classLoader,
      final JavaModule module,
      final ProtectionDomain pd) {
    Advices discovered = advices.findAdvices(builder, type, classLoader);
    if (discovered.isEmpty()) {
      return builder;
    }
    final DynamicType.Builder<?> withHelpers =
        helperInjector.transform(builder, type, classLoader, module, pd);
    return withHelpers.visit(new CallSiteVisitorWrapper(discovered));
  }

  private static class CallSiteVisitorWrapper extends AsmVisitorWrapper.AbstractBase {

    private final Advices advices;

    private CallSiteVisitorWrapper(@Nonnull final Advices advices) {
      this.advices = advices;
    }

    @Override
    public int mergeWriter(final int flags) {
      return flags | COMPUTE_MAXS;
    }

    @Override
    public @Nonnull ClassVisitor wrap(
        @Nonnull final TypeDescription instrumentedType,
        @Nonnull final ClassVisitor classVisitor,
        @Nonnull final Implementation.Context implementationContext,
        @Nonnull final TypePool typePool,
        @Nonnull final FieldList<FieldDescription.InDefinedShape> fields,
        @Nonnull final MethodList<?> methods,
        final int writerFlags,
        final int readerFlags) {
      return new CallSiteClassVisitor(advices, classVisitor);
    }
  }

  private static class CallSiteClassVisitor extends ClassVisitor {

    private final Advices advices;

    private CallSiteClassVisitor(
        @Nonnull final Advices advices, @Nonnull final ClassVisitor delegated) {
      super(ASM_API, delegated);
      this.advices = advices;
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      final MethodVisitor delegated =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      return new CallSiteMethodVisitor(advices, delegated);
    }
  }

  private static class CallSiteMethodVisitor extends MethodVisitor
      implements CallSiteAdvice.MethodHandler {
    private final Advices advices;

    private CallSiteMethodVisitor(
        @Nonnull final Advices advices, @Nonnull final MethodVisitor delegated) {
      super(ASM_API, delegated);
      this.advices = advices;
    }

    @Override
    public void visitMethodInsn(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      CallSiteAdvice advice = advices.findAdvice(owner, name, descriptor);
      if (advice instanceof InvokeAdvice) {
        ((InvokeAdvice) advice).apply(this, opcode, owner, name, descriptor, isInterface);
      } else {
        mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    }

    @Override
    public void visitInvokeDynamicInsn(
        final String name,
        final String descriptor,
        final Handle bootstrapMethodHandle,
        final Object... bootstrapMethodArguments) {
      CallSiteAdvice advice = advices.findAdvice(bootstrapMethodHandle);
      if (advice instanceof InvokeDynamicAdvice) {
        ((InvokeDynamicAdvice) advice)
            .apply(this, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
      } else {
        mv.visitInvokeDynamicInsn(
            name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
      }
    }

    @Override
    public void instruction(final int opcode) {
      mv.visitInsn(opcode);
    }

    @Override
    public void instruction(final int opcode, final int parameter) {
      mv.visitIntInsn(opcode, parameter);
    }

    @Override
    public void instruction(final int opcode, final String type) {
      mv.visitTypeInsn(opcode, type);
    }

    @Override
    public void loadConstant(final Object constant) {
      mv.visitLdcInsn(constant);
    }

    @Override
    public void loadConstantArray(final Object[] array) {
      CallSiteUtils.pushConstantArray(mv, array);
    }

    @Override
    public void field(
        final int opcode, final String owner, final String field, final String descriptor) {
      mv.visitFieldInsn(opcode, owner, field, descriptor);
    }

    @Override
    public void method(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void invokeDynamic(
        final String name,
        final String descriptor,
        final Handle bootstrapMethodHandle,
        final Object... bootstrapMethodArguments) {
      mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void dupParameters(final String methodDescriptor, final StackDupMode mode) {
      final Type method = Type.getMethodType(methodDescriptor);
      if (method.getArgumentTypes().length == 0) {
        return;
      }
      CallSiteUtils.dup(mv, method.getArgumentTypes(), mode);
    }

    @Override
    public void dupParameters(final String methodDescriptor, int[] indices, String owner) {
      final Type method = Type.getMethodType(methodDescriptor);
      Type[] stackArgTypes;
      if (owner != null) {
        stackArgTypes = methodParamTypesWithThis(owner, methodDescriptor);
        int[] newIndices = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
          newIndices[i] = indices[i] + 1;
        }
        indices = newIndices;
      } else {
        stackArgTypes = method.getArgumentTypes();
      }
      CallSiteUtils.dup(mv, stackArgTypes, indices);
    }

    @Override
    public void dupInvoke(
        final String owner, final String methodDescriptor, final StackDupMode mode) {
      final Type[] parameters = methodParamTypesWithThis(owner, methodDescriptor);
      CallSiteUtils.dup(mv, parameters, mode);
    }

    @Override
    public void dupInvoke(String owner, String methodDescriptor, int[] parameterIndices) {
      final Type[] methodParameterTypesWithThis = methodParamTypesWithThis(owner, methodDescriptor);

      int[] parameterIndicesWithThis = new int[parameterIndices.length + 1];
      parameterIndicesWithThis[0] = 0;
      for (int i = 0; i < parameterIndices.length; i++) {
        parameterIndicesWithThis[i + 1] = parameterIndices[i] + 1;
      }
      CallSiteUtils.dup(mv, methodParameterTypesWithThis, parameterIndicesWithThis);
    }

    private Type[] methodParamTypesWithThis(String owner, String methodDescriptor) {
      final Type method = Type.getMethodType(methodDescriptor);
      Type ownerType = Type.getType("L" + owner + ";");
      final Type[] methodParameterTypesWithThis = new Type[method.getArgumentTypes().length + 1];
      methodParameterTypesWithThis[0] = ownerType;
      System.arraycopy(
          method.getArgumentTypes(),
          0,
          methodParameterTypesWithThis,
          1,
          methodParameterTypesWithThis.length - 1);
      return methodParameterTypesWithThis;
    }
  }
}
