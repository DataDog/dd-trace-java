package datadog.trace.instrumentation.vertx_redis_client;

import static net.bytebuddy.matcher.ElementMatchers.none;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
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

@AutoService(InstrumenterModule.class)
public class RequestImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {
  public RequestImplInstrumentation() {
    super("vertx", "vertx-redis-client");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.redis.client.impl.RequestImpl";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new RequestImplVisitorWrapper());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // This advice should never match any methods, and is only here for Muzzle
    transformer.applyAdvice(none(), packageName + ".RequestImplMuzzle");
  }

  // This Transformer will add the Cloneable interface to RequestImpl, as well
  // as a clone method that calls the protected shallow clone method in Object
  public static class RequestImplVisitorWrapper implements AsmVisitorWrapper {
    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
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
      return new ClassVisitor(Opcodes.ASM7, classVisitor) {

        @Override
        public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces) {
          // Add the Cloneable interface
          if (null == interfaces) {
            interfaces = new String[1];
          } else {
            interfaces = Arrays.copyOf(interfaces, interfaces.length + 1);
          }
          interfaces[interfaces.length - 1] = "java/lang/Cloneable";
          cv.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitEnd() {
          // Add a clone method that calls the protected shallow clone method in Object
          //
          // public Object clone() throws CloneNotSupportedException {
          //    return super.clone(); // Object is the super class
          // }
          //
          final MethodVisitor mv =
              cv.visitMethod(
                  Opcodes.ACC_PUBLIC,
                  "clone",
                  "()Ljava/lang/Object;",
                  null,
                  new String[] {"java/lang/CloneNotSupportedException"});
          mv.visitCode();
          mv.visitIntInsn(Opcodes.ALOAD, 0);
          mv.visitMethodInsn(
              Opcodes.INVOKESPECIAL, "java/lang/Object", "clone", "()Ljava/lang/Object;", false);
          mv.visitInsn(Opcodes.ARETURN);
          mv.visitMaxs(0, 0);
          mv.visitEnd();

          cv.visitEnd();
        }
      };
    }
  }
}
