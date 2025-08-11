package datadog.trace.instrumentation.jetty;

import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import java.util.List;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks for a call to <code>org.eclipse.jetty.server.Server#handle([Abstract]HttpConnection)</code>
 * and replaces it with:
 *
 * <pre>
 * if (JettyBlockingHelper.block(this.getRequest(), this.getResponse(), Java8BytecodeBridge.getCurrentContext()) {
 *   // nothing
 * } else {
 *   server.handle(this);
 * }
 * </pre>
 *
 * <p>It needs first to get the index of the context variable that's set when a new span is created.
 */
public class HandleRequestVisitor extends MethodVisitor {
  private static final Logger log = LoggerFactory.getLogger(HandleRequestVisitor.class);

  private final int classVersion;
  private final String connClassInternalName;
  private boolean success;

  public HandleRequestVisitor(
      int api,
      int classVersion,
      DelayLoadsMethodVisitor methodVisitor,
      String connClassInternalName) {
    super(api, methodVisitor);
    this.classVersion = classVersion;
    this.connClassInternalName = connClassInternalName;
  }

  DelayLoadsMethodVisitor delayVisitorDelegate() {
    return (DelayLoadsMethodVisitor) this.mv;
  }

  @Override
  public void visitMethodInsn(
      int opcode, String owner, String name, String descriptor, boolean isInterface) {
    if (opcode == Opcodes.INVOKEVIRTUAL
        && owner.equals("org/eclipse/jetty/server/Server")
        && name.equals("handle")
        && descriptor.equals("(L" + this.connClassInternalName + ";)V")) {
      DelayLoadsMethodVisitor mv = delayVisitorDelegate();
      List<Integer> savedLoads = mv.transferLoads();
      if (savedLoads.size() != 2) {
        mv.commitLoads(savedLoads);
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        return;
      }

      Label afterHandle = new Label();

      // Add Request, Response and Context onto the stack
      super.visitVarInsn(Opcodes.ALOAD, 0);
      super.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          this.connClassInternalName,
          "getRequest",
          "()Lorg/eclipse/jetty/server/Request;",
          false);
      super.visitVarInsn(Opcodes.ALOAD, 0);
      super.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          this.connClassInternalName,
          "getResponse",
          "()Lorg/eclipse/jetty/server/Response;",
          false);

      super.visitMethodInsn(
          INVOKESTATIC,
          Type.getInternalName(Java8BytecodeBridge.class),
          "getCurrentContext",
          "()Ldatadog/context/Context;",
          false);
      // Call JettyBlockingHelper.block(request, response, context)
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          Type.getInternalName(JettyBlockingHelper.class),
          "block",
          "(Lorg/eclipse/jetty/server/Request;Lorg/eclipse/jetty/server/Response;"
              + Type.getDescriptor(Context.class)
              + ")Z",
          false);
      // Jump after handle if blocked
      super.visitJumpInsn(Opcodes.IFNE, afterHandle);

      // Inject default handle instructions
      mv.commitLoads(savedLoads);
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      // Add after handle label
      super.visitLabel(afterHandle);
      if (needsStackFrames()) {
        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
      }
      this.success = true;
      return;
    }

    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
  }

  @Override
  public void visitEnd() {
    if (!success) {
      log.warn(
          "Transformation of Jetty's connection class was not successful. Blocking will likely not work");
    }
    super.visitEnd();
  }

  private boolean needsStackFrames() {
    return this.classVersion >= 50; // 1.6
  }
}
