package datadog.opentelemetry.tooling.shim;

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
import net.bytebuddy.pool.TypePool;

/** ASM visitor which injects our OpenTelemetry shim into the target API. */
public final class OtelShimInjector implements AsmVisitorWrapper {
  static final OtelShimInjector INSTANCE = new OtelShimInjector();

  static final String TRACER_PROVIDER_DESCRIPTOR = "Lio/opentelemetry/api/trace/TracerProvider;";

  static final String CONTEXT_PROPAGATORS_DESCRIPTOR =
      "Lio/opentelemetry/context/propagation/ContextPropagators;";

  static final String CONTEXT_DESCRIPTOR = "Lio/opentelemetry/context/Context;";

  static final String GET_TRACER_PROVIDER_METHOD_DESCRIPTOR = "()" + TRACER_PROVIDER_DESCRIPTOR;

  static final String GET_PROPAGATORS_METHOD_DESCRIPTOR = "()" + CONTEXT_PROPAGATORS_DESCRIPTOR;

  static final String CURRENT_CONTEXT_METHOD_DESCRIPTOR = "()" + CONTEXT_DESCRIPTOR;

  static final String ROOT_CONTEXT_METHOD_DESCRIPTOR = "()" + CONTEXT_DESCRIPTOR;

  static final String SHIM_PACKAGE_PREFIX = "datadog/opentelemetry/shim/";

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
    // for convenience use the same bytecode injector for each class of interest
    // this is safe because the shim-injected methods don't overlap between them
    return new ClassVisitor(Opcodes.ASM7, classVisitor) {
      @Override
      public MethodVisitor visitMethod(
          final int access,
          final String name,
          final String descriptor,
          final String signature,
          final String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if ("getTracerProvider".equals(name)
            && GET_TRACER_PROVIDER_METHOD_DESCRIPTOR.equals(descriptor)) {
          mv.visitCode();
          mv.visitFieldInsn(
              Opcodes.GETSTATIC,
              SHIM_PACKAGE_PREFIX + "trace/OtelTracerProvider",
              "INSTANCE",
              TRACER_PROVIDER_DESCRIPTOR);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitEnd();
          return null;
        } else if ("getPropagators".equals(name)
            && GET_PROPAGATORS_METHOD_DESCRIPTOR.equals(descriptor)) {
          mv.visitCode();
          mv.visitFieldInsn(
              Opcodes.GETSTATIC,
              SHIM_PACKAGE_PREFIX + "context/propagation/OtelContextPropagators",
              "INSTANCE",
              CONTEXT_PROPAGATORS_DESCRIPTOR);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitEnd();
          return null;
        } else if ("current".equals(name) && CURRENT_CONTEXT_METHOD_DESCRIPTOR.equals(descriptor)) {
          mv.visitCode();
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              SHIM_PACKAGE_PREFIX + "context/OtelContext",
              "current",
              CURRENT_CONTEXT_METHOD_DESCRIPTOR,
              false);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitEnd();
          return null;
        } else if ("root".equals(name) && ROOT_CONTEXT_METHOD_DESCRIPTOR.equals(descriptor)) {
          mv.visitCode();
          mv.visitFieldInsn(
              Opcodes.GETSTATIC,
              SHIM_PACKAGE_PREFIX + "context/OtelContext",
              "ROOT",
              CONTEXT_DESCRIPTOR);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitEnd();
          return null;
        } else {
          return mv;
        }
      }
    };
  }
}
