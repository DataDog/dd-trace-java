package datadog.trace.instrumentation.jetty;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;

public class ConnectionHandleRequestVisitor extends ClassVisitor {
  private final String connClassInternalName;
  private int classVersion;

  public ConnectionHandleRequestVisitor(
      int api, ClassVisitor classVisitor, String connClassInternalName) {
    super(api, classVisitor);
    this.connClassInternalName = connClassInternalName;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    this.classVersion = version & 0x00FF;
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    MethodVisitor superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
    if (name.equals("handleRequest") && descriptor.equals("()V")) {
      MethodVisitor mcfMv = new MergeConsecutiveFramesMethodVisitor(this.api, superVisitor);
      DelayLoadsMethodVisitor delayLoadsMethodVisitor =
          new DelayLoadsMethodVisitor(this.api, mcfMv);
      return new HandleRequestVisitor(
          this.api, this.classVersion, delayLoadsMethodVisitor, this.connClassInternalName);
    } else {
      return superVisitor;
    }
  }
}
