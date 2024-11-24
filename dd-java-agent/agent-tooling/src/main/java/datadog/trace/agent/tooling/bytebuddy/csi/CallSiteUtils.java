package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY;
import static net.bytebuddy.jar.asm.Opcodes.AALOAD;
import static net.bytebuddy.jar.asm.Opcodes.AASTORE;
import static net.bytebuddy.jar.asm.Opcodes.ANEWARRAY;
import static net.bytebuddy.jar.asm.Opcodes.BIPUSH;
import static net.bytebuddy.jar.asm.Opcodes.CHECKCAST;
import static net.bytebuddy.jar.asm.Opcodes.DUP;
import static net.bytebuddy.jar.asm.Opcodes.DUP2;
import static net.bytebuddy.jar.asm.Opcodes.DUP2_X1;
import static net.bytebuddy.jar.asm.Opcodes.DUP2_X2;
import static net.bytebuddy.jar.asm.Opcodes.DUP_X1;
import static net.bytebuddy.jar.asm.Opcodes.DUP_X2;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_0;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_1;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_2;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_3;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_4;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_5;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_M1;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;
import static net.bytebuddy.jar.asm.Opcodes.POP;
import static net.bytebuddy.jar.asm.Opcodes.POP2;
import static net.bytebuddy.jar.asm.Opcodes.SIPUSH;
import static net.bytebuddy.jar.asm.Opcodes.SWAP;

import datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

public abstract class CallSiteUtils {

  public static final String OBJET_TYPE = "java/lang/Object";
  private static final BoxingHandler[] BOX_HANDLERS = new BoxingHandler[Type.METHOD + 1];
  private static final Map<String, int[]> STACK_MANIP_TABLE = new HashMap<>();

  static {
    BOX_HANDLERS[Type.BOOLEAN] = new JdkBoxingHandler("java/lang/Boolean", "Z", "booleanValue");
    BOX_HANDLERS[Type.CHAR] = new JdkBoxingHandler("java/lang/Character", "C", "charValue");
    BOX_HANDLERS[Type.BYTE] = new JdkBoxingHandler("java/lang/Byte", "B", "byteValue");
    BOX_HANDLERS[Type.SHORT] = new JdkBoxingHandler("java/lang/Short", "S", "shortValue");
    BOX_HANDLERS[Type.INT] = new JdkBoxingHandler("java/lang/Integer", "I", "intValue");
    BOX_HANDLERS[Type.FLOAT] = new JdkBoxingHandler("java/lang/Float", "F", "floatValue");
    BOX_HANDLERS[Type.LONG] = new JdkBoxingHandler("java/lang/Long", "J", "longValue");
    BOX_HANDLERS[Type.DOUBLE] = new JdkBoxingHandler("java/lang/Double", "D", "doubleValue");

    STACK_MANIP_TABLE.put("12|1", new int[] {SWAP, DUP_X1});
    STACK_MANIP_TABLE.put("12L|1", new int[] {DUP2_X1, POP2, DUP_X2});
    STACK_MANIP_TABLE.put("1L2|1L", new int[] {DUP_X2, POP, DUP2_X1});
    STACK_MANIP_TABLE.put("1L2L|1L", new int[] {DUP2_X2, POP2, DUP2_X2});
    STACK_MANIP_TABLE.put("123|1", new int[] {DUP2_X1, POP2, DUP_X2});
    STACK_MANIP_TABLE.put("123L|1", new int[] {DUP2_X2, POP2, DUP2_X2, POP});
    STACK_MANIP_TABLE.put("1L23|1L", new int[] {DUP2_X2, POP2, DUP2_X2});
    STACK_MANIP_TABLE.put("123|12", new int[] {DUP_X2, POP, DUP2_X1});
    STACK_MANIP_TABLE.put("123L|12", new int[] {DUP2_X2, POP2, DUP2_X2});
    STACK_MANIP_TABLE.put(
        "1L23|1L2", new int[] {DUP2_X2, POP2, DUP2_X2, DUP2_X2, POP2, DUP2_X2, POP});
    STACK_MANIP_TABLE.put("123|13", new int[] {DUP2_X1, POP2, DUP_X2, SWAP, DUP_X1});
    STACK_MANIP_TABLE.put(
        "123L|13L", new int[] {DUP2_X2, POP2, DUP2_X2, POP, DUP_X2, POP, DUP2_X1});
    STACK_MANIP_TABLE.put("1L23|1L3", new int[] {DUP2_X2, POP2, DUP2_X2, DUP2_X1, POP2, DUP_X2});
    STACK_MANIP_TABLE.put("1234|1", new int[] {DUP2_X2, POP2, DUP2_X2, POP});
    STACK_MANIP_TABLE.put("1234|12", new int[] {DUP2_X2, POP2, DUP2_X2});
    STACK_MANIP_TABLE.put("1234|13", new int[] {SWAP, DUP2_X2, POP2, DUP2_X2, POP, SWAP, DUP_X2});
    STACK_MANIP_TABLE.put("1234|14", new int[] {DUP2_X2, POP2, DUP2_X2, POP, SWAP, DUP_X1});
    STACK_MANIP_TABLE.put("1234|124", new int[] {DUP2_X2, POP2, DUP2_X2, DUP2_X1, POP2, DUP_X2});
    STACK_MANIP_TABLE.put(
        "1234|134", new int[] {DUP2_X2, POP2, DUP2_X2, POP, DUP_X2, POP, DUP2_X1});
  }

  private CallSiteUtils() {}

  public static void swap(final MethodVisitor mv, final int secondToLastSize, final int lastSize) {
    if (secondToLastSize == 1 && lastSize == 1) {
      mv.visitInsn(SWAP);
    } else if (secondToLastSize == 2 && lastSize == 2) {
      mv.visitInsn(DUP2_X2);
      mv.visitInsn(POP2);
    } else if (lastSize == 1) {
      mv.visitInsn(DUP_X2);
      mv.visitInsn(POP);
    } else {
      mv.visitInsn(DUP2_X1);
      mv.visitInsn(POP2);
    }
  }

  public static void pushInteger(final MethodVisitor mv, final int value) {
    switch (value) {
      case -1:
        mv.visitInsn(ICONST_M1);
        break;
      case 0:
        mv.visitInsn(ICONST_0);
        break;
      case 1:
        mv.visitInsn(ICONST_1);
        break;
      case 2:
        mv.visitInsn(ICONST_2);
        break;
      case 3:
        mv.visitInsn(ICONST_3);
        break;
      case 4:
        mv.visitInsn(ICONST_4);
        break;
      case 5:
        mv.visitInsn(ICONST_5);
        break;
      default:
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
          mv.visitLdcInsn(value);
        } else if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
          mv.visitIntInsn(SIPUSH, value);
        } else {
          mv.visitIntInsn(BIPUSH, value);
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
    mv.visitTypeInsn(ANEWARRAY, OBJET_TYPE);
    for (int i = 0; i < constants.length; i++) {
      final Object constant = constants[i];
      if (constant != null) {
        mv.visitInsn(DUP);
        pushInteger(mv, i);
        mv.visitLdcInsn(constant);
        box(mv, Type.getType(constant.getClass()));
        mv.visitInsn(AASTORE);
      }
    }
  }

  public static void dup(final MethodVisitor mv, final Type[] parameters, final StackDupMode mode) {
    switch (mode) {
      case COPY:
        dup(mv, parameters);
        break;
      case PREPEND_ARRAY:
      case PREPEND_ARRAY_CTOR:
      case PREPEND_ARRAY_SUPER_CTOR:
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
        mv.visitInsn(DUP);
        break;
      case 2:
        mv.visitInsn(DUP2);
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

  public static void dup(MethodVisitor mv, Type[] argumentTypes, int[] indices) {
    int[] opcodes = lookupParamDupOpcodes(argumentTypes, indices);
    if (opcodes == null) {
      pushArray(mv, argumentTypes.length, argumentTypes);
      loadArray(mv, argumentTypes.length, argumentTypes);
      loadArray(mv, argumentTypes, indices);
      mv.visitInsn(POP); // pop out array
    } else {
      for (int opcode : opcodes) {
        mv.visitInsn(opcode);
      }
    }
  }

  private static int[] lookupParamDupOpcodes(Type[] argumentTypes, int[] indices) {
    if (indices.length == 0) {
      return null;
    }

    // if we don't use index 0, we can reduce the stack manipulation to that
    // of one where there are fewer arguments in the stack
    int minIdx = Integer.MAX_VALUE;
    for (int index : indices) {
      minIdx = Math.min(minIdx, index);
    }

    StringBuilder sb = new StringBuilder();
    for (int i = minIdx; i < argumentTypes.length; i++) {
      sb.append(i + 1 - minIdx);
      Type type = argumentTypes[i];
      if (type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE) {
        sb.append('L');
      }
    }
    sb.append('|');
    for (int index : indices) {
      Type type = argumentTypes[index];
      sb.append(index + 1 - minIdx);
      if (type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE) {
        sb.append('L');
      }
    }

    return STACK_MANIP_TABLE.get(sb.toString());
  }

  private static void dup3(final MethodVisitor mv) {
    mv.visitInsn(DUP);
    mv.visitInsn(DUP2_X2);
    mv.visitInsn(POP2);
    mv.visitInsn(DUP2_X2);
    mv.visitInsn(DUP2_X1);
    mv.visitInsn(POP2);
  }

  private static void dup3_C1_C2(final MethodVisitor mv) {
    mv.visitInsn(DUP2_X1);
    mv.visitInsn(POP2);
    mv.visitInsn(DUP);
    mv.visitInsn(DUP2_X2);
    mv.visitInsn(POP2);
    mv.visitInsn(DUP2_X1);
  }

  private static void dup4(final MethodVisitor mv) {
    mv.visitInsn(DUP2_X2);
    mv.visitInsn(POP2);
    mv.visitInsn(DUP2_X2);
    mv.visitInsn(DUP2_X2);
    mv.visitInsn(POP2);
    mv.visitInsn(DUP2_X2);
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
        mv.visitInsn(DUP);
        loadArray(mv, arraySize, parameters);
        mv.visitInsn(POP);
        break;
      case PREPEND_ARRAY_CTOR:
        // move the array before the uninitialized entry created by NEW and DUP
        // stack start = [uninitialized, uninitialized, arg_0, ..., arg_n]
        // stack   end = [array, uninitialized, uninitialized, arg_0, ..., arg_n]
        mv.visitInsn(DUP_X2);
        loadArray(mv, arraySize, parameters);
        mv.visitInsn(POP);
        break;
      case PREPEND_ARRAY_SUPER_CTOR:
        // move the array before the uninitialized entry
        // stack start = [uninitialized, arg_0, ..., arg_n]
        // stack   end = [array, uninitialized, arg_0, ..., arg_n]
        mv.visitInsn(DUP_X1);
        loadArray(mv, arraySize, parameters);
        mv.visitInsn(POP);
        break;
      case APPEND_ARRAY:
        loadArray(mv, arraySize, parameters);
        break;
      case COPY:
        loadArray(mv, arraySize, parameters);
        loadArray(mv, arraySize, parameters);
        mv.visitInsn(POP);
        break;
    }
  }

  private static void pushArray(
      final MethodVisitor mv, final int arraySize, final Type[] parameters) {
    pushInteger(mv, arraySize);
    mv.visitTypeInsn(ANEWARRAY, OBJET_TYPE);
    for (int i = parameters.length - 1; i >= 0; i--) {
      final Type param = parameters[i];
      final int stackObjectSize = param.getSize();
      // 1. duplicate the array
      mv.visitInsn(stackObjectSize == 1 ? DUP_X1 : DUP_X2);
      swap(mv, stackObjectSize, 1); // [..., STACK_OBJECT, ARRAY]
      // 2. store the index in the array
      mv.visitIntInsn(BIPUSH, i);
      swap(mv, stackObjectSize, 1); // [..., STACK_OBJECT, INDEX]
      // 3. add the element to the array
      box(mv, param);
      mv.visitInsn(AASTORE);
    }
  }

  private static void loadArray(
      final MethodVisitor mv, final int arraySize, final Type[] parameters) {
    for (int i = 0; i < arraySize; i++) {
      final Type type = parameters[i];
      loadNthArgFromArray(mv, type, i);
    }
  }

  private static void loadArray(MethodVisitor mv, Type[] methodArgumentTypes, int[] indices) {
    for (int idx : indices) {
      final Type type = methodArgumentTypes[idx];
      loadNthArgFromArray(mv, type, idx);
    }
  }

  private static void loadNthArgFromArray(MethodVisitor mv, Type type, int argIdx) {
    final int stackObjectSize = type.getSize();
    // 1. duplicate the array
    mv.visitInsn(DUP);
    // 2. load the element from the array
    pushInteger(mv, argIdx);
    mv.visitInsn(AALOAD);
    // 3. cast it to the proper value
    if (!OBJET_TYPE.equals(type.getInternalName())) {
      checkCast(mv, type);
      unbox(mv, type);
    }
    // 4. move the array to the end of the stack
    swap(mv, 1, stackObjectSize); // [..., ARRAY, STACK_OBJECT]
  }

  private static void checkCast(final MethodVisitor mv, final Type parameter) {
    if (parameter.getSort() == Type.OBJECT || parameter.getSort() == Type.ARRAY) {
      mv.visitTypeInsn(CHECKCAST, parameter.getInternalName());
    } else {
      final BoxingHandler handler = BOX_HANDLERS[parameter.getSort()];
      if (handler != null) {
        mv.visitTypeInsn(CHECKCAST, handler.getBoxedType());
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
      mv.visitMethodInsn(INVOKESTATIC, boxedType, boxMethod, boxDescriptor, false);
    }

    @Override
    public void unbox(final MethodVisitor mv) {
      mv.visitMethodInsn(INVOKEVIRTUAL, boxedType, unboxMethod, unboxDescriptor, false);
    }

    @Override
    public String getBoxedType() {
      return boxedType;
    }
  }
}
