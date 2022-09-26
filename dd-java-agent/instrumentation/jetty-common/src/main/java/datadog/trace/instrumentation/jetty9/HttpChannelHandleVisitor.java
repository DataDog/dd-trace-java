package datadog.trace.instrumentation.jetty9;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;

public class HttpChannelHandleVisitor extends ClassVisitor {
  public HttpChannelHandleVisitor(int api, ClassVisitor classVisitor) {
    super(api, classVisitor);
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    MethodVisitor superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
    if ((name.equals("run") || name.equals("handle"))
        && (descriptor.equals("()V") || descriptor.equals("()Z"))) {
      DelayCertainInsMethodVisitor delayLoadsMethodVisitor =
          new DelayCertainInsMethodVisitor(this.api, superVisitor);
      return new HandleVisitor(this.api, delayLoadsMethodVisitor, name);
    } else {
      return superVisitor;
    }
  }
}
