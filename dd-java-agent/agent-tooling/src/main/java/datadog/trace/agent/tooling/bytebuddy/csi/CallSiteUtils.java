package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY;

import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

public abstract class CallSiteUtils {

  public static final String OBJET_TYPE = "java/lang/Object";
  private static final BoxingHandler[] BOX_HANDLERS = new BoxingHandler[Type.METHOD + 1];

  static {
    BOX_HANDLERS[Type.BOOLEAN] = new JdkBoxingHandler("java/lang/Boolean", "Z", "booleanValue");
    BOX_HANDLERS[Type.CHAR] = new JdkBoxingHandler("java/lang/Character", "C", "charValue");
    BOX_HANDLERS[Type.BYTE] = new JdkBoxingHandler("java/lang/Byte", "B", "byteValue");
    BOX_HANDLERS[Type.SHORT] = new JdkBoxingHandler("java/lang/Short", "S", "shortValue");
    BOX_HANDLERS[Type.INT] = new JdkBoxingHandler("java/lang/Integer", "I", "intValue");
    BOX_HANDLERS[Type.FLOAT] = new JdkBoxingHandler("java/lang/Float", "F", "floatValue");
    BOX_HANDLERS[Type.LONG] = new JdkBoxingHandler("java/lang/Long", "J", "longValue");
    BOX_HANDLERS[Type.DOUBLE] = new JdkBoxingHandler("java/lang/Double", "D", "doubleValue");
  }

  private CallSiteUtils() {}

  public static void swap(final MethodVisitor mv, final int secondToLastSize, final int lastSize) {
    if (secondToLastSize == 1 && lastSize == 1) {
      mv.visitInsn(Opcodes.SWAP);
    } else if (secondToLastSize == 2 && lastSize == 2) {
      mv.visitInsn(Opcodes.DUP2_X2);
      mv.visitInsn(Opcodes.POP2);
    } else if (lastSize == 1) {
      mv.visitInsn(Opcodes.DUP_X2);
      mv.visitInsn(Opcodes.POP);
    } else {
      mv.visitInsn(Opcodes.DUP2_X1);
      mv.visitInsn(Opcodes.POP2);
    }
  }

  public static void pushInteger(final MethodVisitor mv, final int value) {
    switch (value) {
      case -1:
        mv.visitInsn(Opcodes.ICONST_M1);
        break;
      case 0:
        mv.visitInsn(Opcodes.ICONST_0);
        break;
      case 1:
        mv.visitInsn(Opcodes.ICONST_1);
        break;
      case 2:
        mv.visitInsn(Opcodes.ICONST_2);
        break;
      case 3:
        mv.visitInsn(Opcodes.ICONST_3);
        break;
      case 4:
        mv.visitInsn(Opcodes.ICONST_4);
        break;
      case 5:
        mv.visitInsn(Opcodes.ICONST_5);
        break;
      default:
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
          mv.visitLdcInsn(value);
        } else if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
          mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
          mv.visitIntInsn(Opcodes.BIPUSH, value);
        }
        break;
    }
  }

  public static void pushConstantArray(final MethodVisitor mv, final Object[] constants) {
    if (constants == null) {
      mv.visitInsn(Opcodes.ACONST_NULL);
      return;
    }
    pushInteger(mv, constants.length);
    mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJET_TYPE);
    for (int i = 0; i < constants.length; i++) {
      final Object constant = constants[i];
      if (constant != null) {
        mv.visitInsn(Opcodes.DUP);
        pushInteger(mv, i);
        mv.visitLdcInsn(constant);
        box(mv, Type.getType(constant.getClass()));
        mv.visitInsn(Opcodes.AASTORE);
      }
    }
  }

  public static void dup(final MethodVisitor mv, final Type[] parameters, final StackDupMode mode) {
    switch (mode) {
      case COPY:
        dup(mv, parameters);
        break;
      case PREPEND_ARRAY:
      case APPEND_ARRAY:
        dupN(mv, parameters, mode);
        break;
    }
  }

  private static void dup(final MethodVisitor mv, final Type[] parameters) {
    int stackSize = 0;
    for (final Type param : parameters) {
      stackSize += param.getSize();
    }
    switch (stackSize) {
      case 0:
        break;
      case 1:
        mv.visitInsn(Opcodes.DUP);
        break;
      case 2:
        mv.visitInsn(Opcodes.DUP2);
        break;
      case 3:
        if (parameters.length == 3 || parameters[0].getSize() == 2) {
          dup3(mv);
        } else {
          dup3_C1_C2(mv);
        }
        break;
      case 4:
        if (parameters.length != 3 || parameters[1].getSize() == 1) {
          dup4(mv);
        } else {
          // the case [C1, C2, C1] cannot be solved with regular stack operations
          dupN(mv, parameters, COPY);
        }
        break;
      default:
        dupN(mv, parameters, COPY);
        break;
    }
  }

  private static void dup3(final MethodVisitor mv) {
    mv.visitInsn(Opcodes.DUP);
    mv.visitInsn(Opcodes.DUP2_X2);
    mv.visitInsn(Opcodes.POP2);
    mv.visitInsn(Opcodes.DUP2_X2);
    mv.visitInsn(Opcodes.DUP2_X1);
    mv.visitInsn(Opcodes.POP2);
  }

  private static void dup3_C1_C2(final MethodVisitor mv) {
    mv.visitInsn(Opcodes.DUP2_X1);
    mv.visitInsn(Opcodes.POP2);
    mv.visitInsn(Opcodes.DUP);
    mv.visitInsn(Opcodes.DUP2_X2);
    mv.visitInsn(Opcodes.POP2);
    mv.visitInsn(Opcodes.DUP2_X1);
  }

  private static void dup4(final MethodVisitor mv) {
    mv.visitInsn(Opcodes.DUP2_X2);
    mv.visitInsn(Opcodes.POP2);
    mv.visitInsn(Opcodes.DUP2_X2);
    mv.visitInsn(Opcodes.DUP2_X2);
    mv.visitInsn(Opcodes.POP2);
    mv.visitInsn(Opcodes.DUP2_X2);
  }

  /**
   * This method duplicates the parameters in the stack by using a temporal array that will only
   * live in the stack
   */
  private static void dupN(
      final MethodVisitor mv, final Type[] parameters, final StackDupMode mode) {
    final int arraySize = parameters.length;
    pushArray(mv, arraySize, parameters);
    switch (mode) {
      case PREPEND_ARRAY:
        mv.visitInsn(Opcodes.DUP);
        loadArray(mv, arraySize, parameters);
        mv.visitInsn(Opcodes.POP);
        break;
      case APPEND_ARRAY:
        loadArray(mv, arraySize, parameters);
        break;
      case COPY:
        loadArray(mv, arraySize, parameters);
        loadArray(mv, arraySize, parameters);
        mv.visitInsn(Opcodes.POP);
        break;
    }
  }

  private static void pushArray(
      final MethodVisitor mv, final int arraySize, final Type[] parameters) {
    pushInteger(mv, arraySize);
    mv.visitTypeInsn(Opcodes.ANEWARRAY, OBJET_TYPE);
    for (int i = parameters.length - 1; i >= 0; i--) {
      final Type param = parameters[i];
      final int stackObjectSize = param.getSize();
      // 1. duplicate the array
      mv.visitInsn(stackObjectSize == 1 ? Opcodes.DUP_X1 : Opcodes.DUP_X2);
      swap(mv, stackObjectSize, 1); // [..., STACK_OBJECT, ARRAY]
      // 2. store the index in the array
      mv.visitIntInsn(Opcodes.BIPUSH, i);
      swap(mv, stackObjectSize, 1); // [..., STACK_OBJECT, INDEX]
      // 3. add the element to the array
      box(mv, param);
      mv.visitInsn(Opcodes.AASTORE);
    }
  }

  private static void loadArray(
      final MethodVisitor mv, final int arraySize, final Type[] parameters) {
    for (int i = 0; i < arraySize; i++) {
      final Type param = parameters[i];
      final int stackObjectSize = param.getSize();
      // 1. duplicate the array
      mv.visitInsn(Opcodes.DUP);
      // 2. load the element from the array
      pushInteger(mv, i);
      mv.visitInsn(Opcodes.AALOAD);
      // 3. cast it to the proper value
      if (!OBJET_TYPE.equals(param.getInternalName())) {
        checkCast(mv, param);
        unbox(mv, param);
      }
      // 4. move the array to the end of the stack
      swap(mv, 1, stackObjectSize); // [..., ARRAY, STACK_OBJECT]
    }
  }

  private static void checkCast(final MethodVisitor mv, final Type parameter) {
    if (parameter.getSort() == Type.OBJECT || parameter.getSort() == Type.ARRAY) {
      mv.visitTypeInsn(Opcodes.CHECKCAST, parameter.getInternalName());
    } else {
      final BoxingHandler handler = BOX_HANDLERS[parameter.getSort()];
      if (handler != null) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, handler.getBoxedType());
      } else {
        throw new IllegalArgumentException("Invalid type for 'CHECKCAST' operation: " + parameter);
      }
    }
  }

  private static void box(final MethodVisitor mv, final Type parameter) {
    final BoxingHandler handler = BOX_HANDLERS[parameter.getSort()];
    if (handler != null) {
      handler.box(mv);
    }
  }

  private static void unbox(final MethodVisitor mv, final Type parameter) {
    final BoxingHandler handler = BOX_HANDLERS[parameter.getSort()];
    if (handler != null) {
      handler.unbox(mv);
    }
  }

  private interface BoxingHandler {
    void box(MethodVisitor mv);

    void unbox(MethodVisitor mv);

    String getBoxedType();
  }

  private static class JdkBoxingHandler implements BoxingHandler {
    private final String boxedType;
    private final String boxMethod;
    private final String unboxMethod;

    private final String boxDescriptor;
    private final String unboxDescriptor;

    private JdkBoxingHandler(
        final String boxedType, final String primitiveType, final String unboxMethod) {
      this(boxedType, primitiveType, "valueOf", unboxMethod);
    }

    private JdkBoxingHandler(
        final String boxedType,
        final String primitiveType,
        final String boxMethod,
        final String unboxMethod) {
      this.boxedType = boxedType;
      this.boxMethod = boxMethod;
      this.unboxMethod = unboxMethod;
      this.boxDescriptor = "(" + primitiveType + ")L" + boxedType + ";";
      this.unboxDescriptor = "()" + primitiveType;
    }

    @Override
    public void box(final MethodVisitor mv) {
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, boxedType, boxMethod, boxDescriptor, false);
    }

    @Override
    public void unbox(final MethodVisitor mv) {
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxedType, unboxMethod, unboxDescriptor, false);
    }

    @Override
    public String getBoxedType() {
      return boxedType;
    }
  }
}
