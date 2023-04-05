package datadog.trace.instrumentation.jacoco;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReflectiveMethodVisitor {
  private static final Logger log = LoggerFactory.getLogger(ReflectiveMethodVisitor.class);
  private static Class methodVisitorClass;
  private static Method visitMethodInsnMethod;
  private static Method visitInsnMethod;
  private static Method visitIntInsnMethod;
  private static Method visitLdcInsnMethod;

  @SuppressWarnings("unchecked")
  public static ReflectiveMethodVisitor wrap(Object mv) throws NoSuchMethodException {
    if (null == methodVisitorClass) {
      methodVisitorClass = mv.getClass();
      visitMethodInsnMethod =
          methodVisitorClass.getMethod(
              "visitMethodInsn",
              int.class,
              String.class,
              String.class,
              String.class,
              boolean.class);
      visitMethodInsnMethod.setAccessible(true);
      visitInsnMethod = methodVisitorClass.getMethod("visitInsn", int.class);
      visitInsnMethod.setAccessible(true);
      visitIntInsnMethod = methodVisitorClass.getMethod("visitIntInsn", int.class, int.class);
      visitIntInsnMethod.setAccessible(true);
      visitLdcInsnMethod = methodVisitorClass.getMethod("visitLdcInsn", Object.class);
      visitLdcInsnMethod.setAccessible(true);
    }
    return new ReflectiveMethodVisitor(mv);
  }

  private final Object mv;

  ReflectiveMethodVisitor(Object mv) {
    this.mv = mv;
  }

  public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf)
      throws InvocationTargetException, IllegalAccessException {
    visitMethodInsnMethod.invoke(mv, opcode, owner, name, desc, itf);
  }

  public void visitLdcInsn(Object cst) throws InvocationTargetException, IllegalAccessException {
    visitLdcInsnMethod.invoke(mv, cst);
  }

  /**
   * Generates the instruction to push the given int value on the stack. Implementation taken from
   * {@link org.objectweb.asm.commons.GeneratorAdapter#push(int)}.
   *
   * @param value the value to be pushed on the stack.
   */
  public void push(int value) throws InvocationTargetException, IllegalAccessException {
    if (value >= -1 && value <= 5) {
      visitInsnMethod.invoke(mv, Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      visitIntInsnMethod.invoke(mv, Opcodes.BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      visitIntInsnMethod.invoke(mv, Opcodes.SIPUSH, value);
    } else {
      visitLdcInsnMethod.invoke(mv, value);
    }
  }
}
