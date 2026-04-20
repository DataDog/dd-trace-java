package datadog.trace.instrumentation.jacoco;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MethodVisitorWrapper {

  private static final Logger log = LoggerFactory.getLogger(MethodVisitorWrapper.class);

  private static final MethodHandle visitMethodInsnHandle;
  private static final MethodHandle visitInsnHandle;
  private static final MethodHandle visitIntInsnHandle;
  private static final MethodHandle visitLdcInsnHandle;
  private static final MethodHandle getTypeHandle;

  static {
    IAgent agent = RT.getAgent();
    Class<? extends IAgent> agentClass = agent.getClass();
    Package jacocoPackage = agentClass.getPackage();
    String jacocoPackageName = jacocoPackage.getName();
    ClassLoader jacocoClassLoader = agentClass.getClassLoader();

    MethodHandles.Lookup lookup = MethodHandles.lookup();
    Class<?> shadedMethodVisitorClass =
        getJacocoClass(jacocoClassLoader, jacocoPackageName, ".asm.MethodVisitor");
    visitMethodInsnHandle =
        accessMethod(
            lookup,
            shadedMethodVisitorClass,
            "visitMethodInsn",
            int.class,
            String.class,
            String.class,
            String.class,
            boolean.class);
    visitInsnHandle = accessMethod(lookup, shadedMethodVisitorClass, "visitInsn", int.class);
    visitIntInsnHandle =
        accessMethod(lookup, shadedMethodVisitorClass, "visitIntInsn", int.class, int.class);
    visitLdcInsnHandle =
        accessMethod(lookup, shadedMethodVisitorClass, "visitLdcInsn", Object.class);

    Class<?> shadedTypeClass = getJacocoClass(jacocoClassLoader, jacocoPackageName, ".asm.Type");
    getTypeHandle = accessMethod(lookup, shadedTypeClass, "getType", String.class);
  }

  private static Class<?> getJacocoClass(
      ClassLoader classLoader, String jacocoPackageName, String classNameSuffix) {
    String className = jacocoPackageName + classNameSuffix;
    try {
      return classLoader.loadClass(className);

    } catch (Throwable throwable) {
      log.error("Could not load Jacoco class: {}", className, throwable);
      return null;
    }
  }

  private static MethodHandle accessMethod(
      MethodHandles.Lookup lookup, Class<?> clazz, String methodName, Class<?>... arguments) {
    if (clazz == null) {
      return null;
    }
    try {
      Method method = clazz.getMethod(methodName, arguments);
      method.setAccessible(true);
      return lookup.unreflect(method);
    } catch (Throwable throwable) {
      log.error(
          "Could not find method {} with arguments {} in class {}",
          methodName,
          Arrays.toString(arguments),
          clazz.getName(),
          throwable);
      return null;
    }
  }

  public static MethodVisitorWrapper wrap(Object mv) {
    return new MethodVisitorWrapper(mv);
  }

  private final Object mv;

  MethodVisitorWrapper(Object mv) {
    this.mv = mv;
  }

  public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf)
      throws Throwable {
    visitMethodInsnHandle.invoke(mv, opcode, owner, name, desc, itf);
  }

  public void visitLdcInsn(Object cst) throws Throwable {
    visitLdcInsnHandle.invoke(mv, cst);
  }

  /**
   * Generates the instruction to push the given int value on the stack. Implementation taken from
   * {@link org.objectweb.asm.commons.GeneratorAdapter#push(int)}.
   *
   * @param value the value to be pushed on the stack.
   */
  public void push(int value) throws Throwable {
    if (value >= -1 && value <= 5) {
      visitInsnHandle.invoke(mv, Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      visitIntInsnHandle.invoke(mv, Opcodes.BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      visitIntInsnHandle.invoke(mv, Opcodes.SIPUSH, value);
    } else {
      visitLdcInsnHandle.invoke(mv, value);
    }
  }

  public void pushClass(String className) throws Throwable {
    Object clazz = getTypeHandle.invoke('L' + className + ';');
    visitLdcInsnHandle.invoke(mv, clazz);
  }
}
