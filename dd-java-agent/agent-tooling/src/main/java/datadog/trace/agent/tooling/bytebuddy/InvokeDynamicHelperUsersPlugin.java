package datadog.trace.agent.tooling.bytebuddy;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;

import datadog.trace.agent.tooling.UsesInvokeDynamicHelpers;
import datadog.trace.agent.tooling.csi.CallSite;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Looks for users of invokedynamic helpers and replaces static calls to them with invokedynamic
 * calls.
 *
 * <p>Currently, it looks for classes annotated with {@link CallSite} and {@link
 * UsesInvokeDynamicHelpers}.
 */
public class InvokeDynamicHelperUsersPlugin extends Plugin.ForElementMatcher {
  public InvokeDynamicHelperUsersPlugin(File targetDir) {
    super(isAnnotatedWith(CallSite.class).or(isAnnotatedWith(UsesInvokeDynamicHelpers.class)));
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(new ReplaceHelperCallsVisitorWrapper());
  }

  @Override
  public void close() throws IOException {}

  public static class ReplaceHelperCallsVisitorWrapper implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
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
      return new ReplaceHelperCallsClassVisitor(classVisitor, typePool);
    }
  }

  public static class ReplaceHelperCallsClassVisitor extends ClassVisitor {
    private final TypePool typePool;

    protected ReplaceHelperCallsClassVisitor(ClassVisitor classVisitor, TypePool typePool) {
      super(OpenedClassReader.ASM_API, classVisitor);
      this.typePool = typePool;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor superMV = super.visitMethod(access, name, descriptor, signature, exceptions);
      return new ReplaceHelperCallsMethodVisitor(api, superMV, typePool);
    }
  }

  public static class ReplaceHelperCallsMethodVisitor extends MethodVisitor {
    private final TypePool typePool;

    protected ReplaceHelperCallsMethodVisitor(
        int api, MethodVisitor methodVisitor, TypePool typePool) {
      super(api, methodVisitor);
      this.typePool = typePool;
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (opcode != Opcodes.INVOKESTATIC) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      MethodDescription referredToMeth = findMethod(owner, name, descriptor);
      if (referredToMeth == null) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }
      if (!isAnnotatedWithInvokeDynamicHelper(referredToMeth)) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      Handle bootstrapHandle =
          new Handle(
              Opcodes.H_INVOKESTATIC,
              "datadog/trace/api/iast/InvokeDynamicHelperRegistry",
              "bootstrap",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
              false);
      String helperKey = owner.replace('/', '.') + '\0' + name;
      super.visitInvokeDynamicInsn(name, descriptor, bootstrapHandle, helperKey);
    }

    private boolean isAnnotatedWithInvokeDynamicHelper(MethodDescription referredToMeth) {
      for (AnnotationDescription ann : referredToMeth.getDeclaredAnnotations()) {
        if (ann.getAnnotationType()
            .getName()
            .equals("datadog.trace.api.iast.InvokeDynamicHelper")) {
          return true;
        }
      }
      return false;
    }

    private MethodDescription findMethod(String owner, String name, String descriptor) {
      TypePool.Resolution resolution = this.typePool.describe(owner.replace('/', '.'));
      if (!resolution.isResolved()) {
        return null;
      }
      TypeDescription ownerDescr = resolution.resolve();
      for (MethodDescription.InDefinedShape meth : ownerDescr.getDeclaredMethods()) {
        if (meth.getName().equals(name) && meth.getDescriptor().equals(descriptor)) {
          return meth;
        }
      }
      return null;
    }
  }
}
