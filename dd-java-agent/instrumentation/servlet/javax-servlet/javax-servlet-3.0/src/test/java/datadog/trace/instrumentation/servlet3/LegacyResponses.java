package datadog.trace.instrumentation.servlet3;

import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.ALOAD;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESPECIAL;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.IRETURN;
import static net.bytebuddy.jar.asm.Opcodes.RETURN;
import static net.bytebuddy.jar.asm.Opcodes.V1_8;

import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;

/**
 * Response classes compiled as if against Servlet 2.5, which has no header accessors. javac can't
 * produce them against the 3.0 API on the test classpath, so they're generated as bytecode.
 */
final class LegacyResponses {
  private LegacyResponses() {}

  /** Implements {@link HttpServletResponse} but none of its methods. */
  static HttpServletResponse oldResponse(String name) throws Exception {
    return (HttpServletResponse)
        define(name, "java/lang/Object", "()V", true, false, -1).getConstructor().newInstance();
  }

  /** Like {@link #oldResponse}, but implements {@code getStatus}, returning {@code status}. */
  static HttpServletResponse oldResponseWithStatus(String name, int status) throws Exception {
    return (HttpServletResponse)
        define(name, "java/lang/Object", "()V", true, false, status).getConstructor().newInstance();
  }

  /** Like {@link #oldResponse}, but implements {@code getHeaderNames} (one name) only. */
  static HttpServletResponse partialResponse(String name) throws Exception {
    return (HttpServletResponse)
        define(name, "java/lang/Object", "()V", true, true, -1).getConstructor().newInstance();
  }

  /** A {@code HttpServletResponseWrapper} subclass that, like 2.5 code, overrides nothing. */
  static HttpServletResponse oldWrapper(String name, HttpServletResponse delegate)
      throws Exception {
    return (HttpServletResponse)
        define(
                name,
                "javax/servlet/http/HttpServletResponseWrapper",
                "(Ljavax/servlet/http/HttpServletResponse;)V",
                false,
                false,
                -1)
            .getConstructor(HttpServletResponse.class)
            .newInstance(delegate);
  }

  /**
   * The constructor just forwards its (optional) argument to the same-shaped super constructor. A
   * negative {@code status} leaves {@code getStatus} out.
   */
  private static Class<?> define(
      String name,
      String superName,
      String ctorDesc,
      boolean implementsResponse,
      boolean withHeaderNames,
      int status)
      throws Exception {
    String internalName = "test/" + name;
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        V1_8,
        ACC_PUBLIC,
        internalName,
        null,
        superName,
        implementsResponse ? new String[] {"javax/servlet/http/HttpServletResponse"} : null);
    MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    if (!"()V".equals(ctorDesc)) {
      init.visitVarInsn(ALOAD, 1);
    }
    init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", ctorDesc, false);
    init.visitInsn(RETURN);
    init.visitMaxs(2, 2);
    init.visitEnd();
    if (withHeaderNames) {
      MethodVisitor names =
          writer.visitMethod(ACC_PUBLIC, "getHeaderNames", "()Ljava/util/Collection;", null, null);
      names.visitCode();
      names.visitLdcInsn("x-a");
      names.visitMethodInsn(
          INVOKESTATIC,
          "java/util/Collections",
          "singletonList",
          "(Ljava/lang/Object;)Ljava/util/List;",
          false);
      names.visitInsn(ARETURN);
      names.visitMaxs(1, 1);
      names.visitEnd();
    }
    if (status >= 0) {
      MethodVisitor getStatus = writer.visitMethod(ACC_PUBLIC, "getStatus", "()I", null, null);
      getStatus.visitCode();
      getStatus.visitLdcInsn(status);
      getStatus.visitInsn(IRETURN);
      getStatus.visitMaxs(1, 1);
      getStatus.visitEnd();
    }
    writer.visitEnd();
    byte[] bytes = writer.toByteArray();
    return new ClassLoader(LegacyResponses.class.getClassLoader()) {
      Class<?> define() {
        return defineClass("test." + name, bytes, 0, bytes.length);
      }
    }.define();
  }
}
