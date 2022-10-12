package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSite;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

public class CsiAdviceTransformationPlugin extends Plugin.ForElementMatcher {
  public CsiAdviceTransformationPlugin(File targetDir) {
    super(ElementMatchers.isAnnotatedWith(CallSite.class));
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
      List<MethodDescription> relevantMethods = new ArrayList<>();
      for (MethodDescription md : methods) {
        for (AnnotationDescription ann : md.getDeclaredAnnotations()) {
          if (ann.getAnnotationType()
              .getName()
              .startsWith("datadog.trace.agent.tooling.csi.CallSite$")) {
            relevantMethods.add(md);
          }
        }
      }

      return new ReplaceHelperCallsClassVisitor(classVisitor, relevantMethods, typePool);
    }
  }

  public static class ReplaceHelperCallsClassVisitor extends ClassVisitor {
    private final List<MethodDescription> relevantMethods;
    private final TypePool typePool;

    protected ReplaceHelperCallsClassVisitor(
        ClassVisitor classVisitor, List<MethodDescription> methods, TypePool typePool) {
      super(OpenedClassReader.ASM_API, classVisitor);
      this.relevantMethods = methods;
      this.typePool = typePool;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor superMV = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (isRelevantMethod(name, descriptor)) {
        return new ReplaceHelperCallsMethodVisitor(api, superMV, typePool);
      } else {
        return superMV;
      }
    }

    private boolean isRelevantMethod(String name, String descriptor) {
      for (MethodDescription meth : this.relevantMethods) {
        if (meth.getName().equals(name) && meth.getDescriptor().equals(descriptor)) {
          return true;
        }
      }

      return false;
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
      if (!isAnnotatedWithCallSiteHelper(referredToMeth)) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      Handle bootstrapHandle =
          new Handle(
              Opcodes.H_INVOKESTATIC,
              "datadog/trace/api/iast/CallSiteHelperRegistry",
              "bootstrap",
              "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
              false);
      String helperKey = owner.replace('/', '.') + '\0' + name;
      super.visitInvokeDynamicInsn(name, descriptor, bootstrapHandle, helperKey);
    }

    private boolean isAnnotatedWithCallSiteHelper(MethodDescription referredToMeth) {
      for (AnnotationDescription ann : referredToMeth.getDeclaredAnnotations()) {
        if (ann.getAnnotationType().getName().equals("datadog.trace.api.iast.CallSiteHelper")) {
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
