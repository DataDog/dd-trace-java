package datadog.trace.agent.tooling.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.APPEND_ARRAY;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.COPY;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY_CTOR;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY_SUPER_CTOR;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forBoolean;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forByte;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forChar;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forDouble;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forFloat;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forInt;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forLong;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forObject;
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.forShort;
import static net.bytebuddy.jar.asm.Type.BOOLEAN_TYPE;
import static net.bytebuddy.jar.asm.Type.BYTE_TYPE;
import static net.bytebuddy.jar.asm.Type.CHAR_TYPE;
import static net.bytebuddy.jar.asm.Type.DOUBLE_TYPE;
import static net.bytebuddy.jar.asm.Type.FLOAT_TYPE;
import static net.bytebuddy.jar.asm.Type.INT_TYPE;
import static net.bytebuddy.jar.asm.Type.LONG_TYPE;
import static net.bytebuddy.jar.asm.Type.SHORT_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteUtils;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

/**
 * Stack items in tables use the {@code kind(value)} notation parsed by {@link
 * #toStackObject(String)}, e.g. {@code int(1)}, {@code long(2)} or {@code String(PI = )}.
 */
public class CallSiteUtilsTest extends DDJavaSpecification {

  private static final String OBJECT_ARRAY_DESCRIPTOR = "[Ljava/lang/Object;";

  @TableTest({
    "scenario            | value  | opcode   ",
    "constant -1         | -1     | ICONST_M1",
    "constant 0          | 0      | ICONST_0 ",
    "constant 1          | 1      | ICONST_1 ",
    "constant 2          | 2      | ICONST_2 ",
    "constant 3          | 3      | ICONST_3 ",
    "constant 4          | 4      | ICONST_4 ",
    "constant 5          | 5      | ICONST_5 ",
    "Short.MIN_VALUE - 1 | -32769 | LDC      ",
    "Short.MIN_VALUE     | -32768 | SIPUSH   ",
    "Short.MAX_VALUE + 1 | 32768  | LDC      ",
    "Short.MAX_VALUE     | 32767  | SIPUSH   ",
    "Byte.MIN_VALUE - 1  | -129   | SIPUSH   ",
    "Byte.MIN_VALUE      | -128   | BIPUSH   ",
    "Byte.MAX_VALUE + 1  | 128    | SIPUSH   ",
    "Byte.MAX_VALUE      | 127    | BIPUSH   "
  })
  void testPushIntValueOntoStack(int value, String opcodeName) {
    // setup
    int opcode = opcode(opcodeName);
    MethodVisitor visitor = mock(MethodVisitor.class);

    // when
    CallSiteUtils.pushInteger(visitor, value);

    // then
    if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
      verify(visitor).visitInsn(opcode);
    } else if (opcode == Opcodes.LDC) {
      verify(visitor).visitLdcInsn(value);
    } else {
      verify(visitor).visitIntInsn(opcode, value);
    }
    verifyNoMoreInteractions(visitor);
  }

  @TableTest({
    "scenario    | secondToLastSize | lastSize | opcodes        ",
    "[..., 1, 1] | 1                | 1        | [SWAP]         ",
    "[..., 2, 2] | 2                | 2        | [DUP2_X2, POP2]",
    "[..., 1, 2] | 1                | 2        | [DUP2_X1, POP2]",
    "[..., 2, 1] | 2                | 1        | [DUP_X2, POP]  "
  })
  void testSwap(int secondToLastSize, int lastSize, List<String> opcodes) {
    // setup
    MethodVisitor visitor = mock(MethodVisitor.class);

    // when
    CallSiteUtils.swap(visitor, secondToLastSize, lastSize);

    // then
    for (String opcodeName : opcodes) {
      verify(visitor).visitInsn(opcode(opcodeName));
    }
    verifyNoMoreInteractions(visitor);
  }

  @TableTest({
    "scenario                      | items                                                  ",
    "int                           | [int(1)]                                               ",
    "int, int                      | [int(1), int(2)]                                       ",
    "long                          | [long(1)]                                              ",
    "long, long                    | [long(1), long(2)]                                     ",
    "int, long                     | [int(1), long(2)]                                      ",
    "int, int, long                | [int(1), int(2), long(3)]                              ",
    "int, int, int, long           | [int(1), int(2), int(3), long(4)]                      ",
    "String, double, char, boolean | ['String(PI = )', double(3.14), char(?), boolean(true)]"
  })
  void testStackCloneOf(List<StackObject> items) {
    // setup
    List<StackObject> stack = buildStack(items);
    MethodVisitor visitor = mockMethodVisitor(stack);
    List<StackObject> expected = new ArrayList<>(items);
    expected.addAll(items);

    // when
    CallSiteUtils.dup(visitor, types(items), COPY);

    // then
    List<StackObject> result = fromStack(stack);
    assertEquals(expected, result);
  }

  @TableTest({
    "scenario             | items                             | indices   | expectedExtra                ",
    "'12|1'               | [int(1), byte(2)]                 | [0]       | [int(1)]                     ",
    "'12L|1'              | [int(1), long(2)]                 | [0]       | [int(1)]                     ",
    "'1L2|1L'             | [long(1), int(2)]                 | [0]       | [long(1)]                    ",
    "'1L2L|1L'            | [long(1), long(2)]                | [0]       | [long(1)]                    ",
    "'123|1'              | [int(1), int(2), int(3)]          | [0]       | [int(1)]                     ",
    "'123L|1'             | [int(1), int(2), long(3)]         | [0]       | [int(1)]                     ",
    "'1L23|1L'            | [long(1), int(2), int(3)]         | [0]       | [long(1)]                    ",
    "'123|12'             | [int(1), int(2), int(3)]          | [0, 1]    | [int(1), int(2)]             ",
    "'123L|12'            | [int(1), int(2), long(3)]         | [0, 1]    | [int(1), int(2)]             ",
    "'1L23|1L2'           | [long(1), int(2), int(3)]         | [0, 1]    | [long(1), int(2)]            ",
    "'123|13'             | [int(1), int(2), int(3)]          | [0, 2]    | [int(1), int(3)]             ",
    "'123L|13L'           | [int(1), int(2), long(3)]         | [0, 2]    | [int(1), long(3)]            ",
    "'1L23|1L3'           | [long(1), int(2), int(3)]         | [0, 2]    | [long(1), int(3)]            ",
    "'1234|1'             | [int(1), int(2), int(3), int(4)]  | [0]       | [int(1)]                     ",
    "'1234|12'            | [int(1), int(2), int(3), int(4)]  | [0, 1]    | [int(1), int(2)]             ",
    "'1234|13'            | [int(1), int(2), int(3), int(4)]  | [0, 2]    | [int(1), int(3)]             ",
    "'1234|14'            | [int(1), int(2), int(3), int(4)]  | [0, 3]    | [int(1), int(4)]             ",
    "'1234|124'           | [int(1), int(2), int(3), int(4)]  | [0, 1, 3] | [int(1), int(2), int(4)]     ",
    "'1234|134'           | [int(1), int(2), int(3), int(4)]  | [0, 2, 3] | [int(1), int(3), int(4)]     ",
    "// fallback cases                                                                                   ",
    "'fallback 12L34|134' | [int(1), long(2), int(3), int(4)] | [0, 2, 3] | [int(1), int(3), int(4)]     ",
    "'fallback 123|331'   | [int(1), int(2), int(3)]          | [2, 2, 0] | [int(3), int(3), int(1)]     "
  })
  void testDupWithExplicitIndices(
      List<StackObject> items, int[] indices, List<StackObject> expectedExtra) {
    // setup
    List<StackObject> stack = buildStack(items);
    MethodVisitor visitor = mockMethodVisitor(stack);
    List<StackObject> expected = new ArrayList<>(items);
    expected.addAll(expectedExtra);

    // when
    CallSiteUtils.dup(visitor, types(items), indices);

    // then
    List<StackObject> result = fromStack(stack);
    assertEquals(expected, result);
  }

  @TableTest({
    "scenario                      | items                                                  ",
    "int                           | [int(1)]                                               ",
    "int, int                      | [int(1), int(2)]                                       ",
    "long                          | [long(1)]                                              ",
    "long, long                    | [long(1), long(2)]                                     ",
    "int, long                     | [int(1), long(2)]                                      ",
    "int, int, long                | [int(1), int(2), long(3)]                              ",
    "int, int, int, long           | [int(1), int(2), int(3), long(4)]                      ",
    "String, double, char, boolean | ['String(PI = )', double(3.14), char(?), boolean(true)]"
  })
  void testStackDupWithArrayBefore(List<StackObject> items) {
    // setup
    List<StackObject> stack = buildStack(items);
    MethodVisitor visitor = mockMethodVisitor(stack);

    // when
    CallSiteUtils.dup(visitor, types(items), PREPEND_ARRAY);

    // then: the first element of the stack should be an array with the parameters
    assertArrayOfValues(items, stack.remove(0));

    // then: the rest of the array should contain the expected values
    List<StackObject> result = fromStack(stack);
    assertEquals(items, result);
  }

  @TableTest({
    "scenario                      | items                                                  ",
    "int                           | [int(1)]                                               ",
    "int, int                      | [int(1), int(2)]                                       ",
    "long                          | [long(1)]                                              ",
    "long, long                    | [long(1), long(2)]                                     ",
    "int, long                     | [int(1), long(2)]                                      ",
    "int, int, long                | [int(1), int(2), long(3)]                              ",
    "int, int, int, long           | [int(1), int(2), int(3), long(4)]                      ",
    "String, double, char, boolean | ['String(PI = )', double(3.14), char(?), boolean(true)]"
  })
  void testStackDupWithArrayAfter(List<StackObject> items) {
    // setup
    List<StackObject> stack = buildStack(items);
    MethodVisitor visitor = mockMethodVisitor(stack);

    // when
    CallSiteUtils.dup(visitor, types(items), APPEND_ARRAY);

    // then: the last element of the stack should be an array with the parameters
    assertArrayOfValues(items, stack.remove(stack.size() - 1));

    // then: the rest of the array should contain the expected values
    List<StackObject> result = fromStack(stack);
    assertEquals(items, result);
  }

  @TableTest({
    "scenario                                | items                                                                                ",
    "NEW, DUP, int                           | ['String(NEW)', 'String(DUP)', int(1)]                                               ",
    "NEW, DUP, int, int                      | ['String(NEW)', 'String(DUP)', int(1), int(2)]                                       ",
    "NEW, DUP, long                          | ['String(NEW)', 'String(DUP)', long(1)]                                              ",
    "NEW, DUP, long, long                    | ['String(NEW)', 'String(DUP)', long(1), long(2)]                                     ",
    "NEW, DUP, int, long                     | ['String(NEW)', 'String(DUP)', int(1), long(2)]                                      ",
    "NEW, DUP, int, int, long                | ['String(NEW)', 'String(DUP)', int(1), int(2), long(3)]                              ",
    "NEW, DUP, int, int, int, long           | ['String(NEW)', 'String(DUP)', int(1), int(2), int(3), long(4)]                      ",
    "NEW, DUP, String, double, char, boolean | ['String(NEW)', 'String(DUP)', 'String(PI = )', double(3.14), char(?), boolean(true)]"
  })
  void testStackDupWithArrayBeforeCtor(List<StackObject> items) {
    // setup
    List<StackObject> expected = items.subList(2, items.size());
    List<StackObject> stack = buildStack(items);
    MethodVisitor visitor = mockMethodVisitor(stack);

    // when
    CallSiteUtils.dup(visitor, types(expected), PREPEND_ARRAY_CTOR);

    // then: the first element of the stack should be an array with the parameters
    assertArrayOfValues(expected, stack.remove(0));

    // then: the rest of the stack should contain the original values
    List<StackObject> result = fromStack(stack);
    assertEquals(items, result);
  }

  @TableTest({
    "scenario                                  | items                                                                           ",
    "uninitialized, int                        | ['String(uninitialized)', int(1)]                                               ",
    "uninitialized, int, int                   | ['String(uninitialized)', int(1), int(2)]                                       ",
    "uninitialized, long                       | ['String(uninitialized)', long(1)]                                              ",
    "uninitialized, long, long                 | ['String(uninitialized)', long(1), long(2)]                                     ",
    "uninitialized, int, long                  | ['String(uninitialized)', int(1), long(2)]                                      ",
    "uninitialized, int, int, long             | ['String(uninitialized)', int(1), int(2), long(3)]                              ",
    "uninitialized, int, int, int, long        | ['String(uninitialized)', int(1), int(2), int(3), long(4)]                      ",
    "uninitialized, String, double, char, bool | ['String(uninitialized)', 'String(PI = )', double(3.14), char(?), boolean(true)]"
  })
  void testStackDupWithArrayBeforeSuperCtor(List<StackObject> items) {
    // setup
    List<StackObject> expected = items.subList(1, items.size());
    List<StackObject> stack = buildStack(items);
    MethodVisitor visitor = mockMethodVisitor(stack);

    // when
    CallSiteUtils.dup(visitor, types(expected), PREPEND_ARRAY_SUPER_CTOR);

    // then: the first element of the stack should be an array with the parameters
    assertArrayOfValues(expected, stack.remove(0));

    // then: the rest of the stack should contain the original values
    List<StackObject> result = fromStack(stack);
    assertEquals(items, result);
  }

  /** Parses a {@code kind(value)} table cell into a {@link StackObject}. */
  @TypeConverter
  public static StackObject toStackObject(String cell) {
    int open = cell.indexOf('(');
    if (open < 0 || !cell.endsWith(")")) {
      throw new IllegalArgumentException("Expected kind(value) but got: " + cell);
    }
    String kind = cell.substring(0, open);
    String value = cell.substring(open + 1, cell.length() - 1);
    switch (kind) {
      case "boolean":
        return forBoolean(Boolean.parseBoolean(value));
      case "char":
        return forChar(value.charAt(0));
      case "byte":
        return forByte(Byte.parseByte(value));
      case "short":
        return forShort(Short.parseShort(value));
      case "int":
        return forInt(Integer.parseInt(value));
      case "long":
        return forLong(Long.parseLong(value));
      case "float":
        return forFloat(Float.parseFloat(value));
      case "double":
        return forDouble(Double.parseDouble(value));
      case "String":
        return forObject(value);
      default:
        throw new IllegalArgumentException("Unknown stack object: " + cell);
    }
  }

  private static int opcode(String name) {
    switch (name) {
      case "ICONST_M1":
        return Opcodes.ICONST_M1;
      case "ICONST_0":
        return Opcodes.ICONST_0;
      case "ICONST_1":
        return Opcodes.ICONST_1;
      case "ICONST_2":
        return Opcodes.ICONST_2;
      case "ICONST_3":
        return Opcodes.ICONST_3;
      case "ICONST_4":
        return Opcodes.ICONST_4;
      case "ICONST_5":
        return Opcodes.ICONST_5;
      case "LDC":
        return Opcodes.LDC;
      case "SIPUSH":
        return Opcodes.SIPUSH;
      case "BIPUSH":
        return Opcodes.BIPUSH;
      case "SWAP":
        return Opcodes.SWAP;
      case "POP":
        return Opcodes.POP;
      case "POP2":
        return Opcodes.POP2;
      case "DUP_X2":
        return Opcodes.DUP_X2;
      case "DUP2_X1":
        return Opcodes.DUP2_X1;
      case "DUP2_X2":
        return Opcodes.DUP2_X2;
      default:
        throw new IllegalArgumentException("Unknown opcode: " + name);
    }
  }

  private static Type[] types(List<StackObject> items) {
    return items.stream().map(item -> item.type).toArray(Type[]::new);
  }

  private static void assertArrayOfValues(List<StackObject> expected, StackObject arrayFromStack) {
    assertEquals(OBJECT_ARRAY_DESCRIPTOR, arrayFromStack.type.getDescriptor());
    Object[] array = (Object[]) arrayFromStack.getValue();
    assertEquals(expected.size(), array.length);
    for (int index = 0; index < array.length; index++) {
      StackObject arrayItem = (StackObject) array[index];
      // some of the items might be boxed so be careful
      assertEquals(expected.get(index).getValue(), arrayItem.getValue());
    }
  }

  private static boolean isCategoryOne(StackObject value) {
    return !value.isCategoryTwo();
  }

  private static boolean isCategoryTwo(StackObject value1, StackObject value2) {
    return value1.equals(value2) && value1.isCategoryTwo();
  }

  private static List<StackObject> buildStack(List<StackObject> items) {
    List<StackObject> stack = new ArrayList<>();
    for (StackObject value : items) {
      value.addToStack(stack);
    }
    return stack;
  }

  private static List<StackObject> fromStack(List<StackObject> list) {
    List<StackObject> result = new ArrayList<>();
    for (int index = 0; index < list.size(); index++) {
      StackObject stack = list.get(index);
      result.add(stack);
      if (stack.isCategoryTwo()) {
        StackObject next = list.get(++index);
        assertEquals(stack, next, "Category II objects take two places in the stack");
      }
    }
    return result;
  }

  /**
   * Mocks a {@link MethodVisitor} that runs the instructions emitted by {@link CallSiteUtils} on
   * the simulated {@code stack}; any other call is rejected.
   */
  private static MethodVisitor mockMethodVisitor(List<StackObject> stack) {
    StackSimulator simulator = new StackSimulator(stack);
    MethodVisitor visitor =
        mock(
            MethodVisitor.class,
            invocation -> {
              throw new IllegalArgumentException(
                  "Unexpected ASM visitor call " + invocation.getMethod().getName());
            });

    doAnswer(answerVoid(it -> simulator.handleDup())).when(visitor).visitInsn(Opcodes.DUP);
    doAnswer(answerVoid(it -> simulator.handleDupX1())).when(visitor).visitInsn(Opcodes.DUP_X1);
    doAnswer(answerVoid(it -> simulator.handleDupX2())).when(visitor).visitInsn(Opcodes.DUP_X2);
    doAnswer(answerVoid(it -> simulator.handleDup2())).when(visitor).visitInsn(Opcodes.DUP2);
    doAnswer(answerVoid(it -> simulator.handleDup2X1())).when(visitor).visitInsn(Opcodes.DUP2_X1);
    doAnswer(answerVoid(it -> simulator.handleDup2X2())).when(visitor).visitInsn(Opcodes.DUP2_X2);
    doAnswer(answerVoid(it -> simulator.handlePop())).when(visitor).visitInsn(Opcodes.POP);
    doAnswer(answerVoid(it -> simulator.handlePop2())).when(visitor).visitInsn(Opcodes.POP2);
    doAnswer(answerVoid(it -> simulator.handleSwap())).when(visitor).visitInsn(Opcodes.SWAP);
    doAnswer(answerVoid(it -> stack.add(forInt(-1)))).when(visitor).visitInsn(Opcodes.ICONST_M1);
    doAnswer(answerVoid(it -> stack.add(forInt(0)))).when(visitor).visitInsn(Opcodes.ICONST_0);
    doAnswer(answerVoid(it -> stack.add(forInt(1)))).when(visitor).visitInsn(Opcodes.ICONST_1);
    doAnswer(answerVoid(it -> stack.add(forInt(2)))).when(visitor).visitInsn(Opcodes.ICONST_2);
    doAnswer(answerVoid(it -> stack.add(forInt(3)))).when(visitor).visitInsn(Opcodes.ICONST_3);
    doAnswer(answerVoid(it -> stack.add(forInt(4)))).when(visitor).visitInsn(Opcodes.ICONST_4);
    doAnswer(answerVoid(it -> stack.add(forInt(5)))).when(visitor).visitInsn(Opcodes.ICONST_5);
    doAnswer(answerVoid((Integer opcode, Integer operand) -> stack.add(forInt(operand))))
        .when(visitor)
        .visitIntInsn(eq(Opcodes.BIPUSH), anyInt());
    doAnswer(answerVoid(it -> simulator.handleNewArray()))
        .when(visitor)
        .visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
    doAnswer(answerVoid(it -> simulator.handleArrayStore()))
        .when(visitor)
        .visitInsn(Opcodes.AASTORE);
    doAnswer(answerVoid(it -> simulator.handleArrayLoad())).when(visitor).visitInsn(Opcodes.AALOAD);
    doAnswer(answerVoid((Integer opcode, String type) -> simulator.handleCheckCast(type)))
        .when(visitor)
        .visitTypeInsn(eq(Opcodes.CHECKCAST), any());
    doAnswer(
            invocation -> {
              simulator.handleBoxing(invocation.getArgument(1));
              return null;
            })
        .when(visitor)
        .visitMethodInsn(eq(Opcodes.INVOKESTATIC), any(), eq("valueOf"), any(), eq(false));
    doAnswer(
            invocation -> {
              simulator.handleUnBoxing(invocation.getArgument(1), invocation.getArgument(2));
              return null;
            })
        .when(visitor)
        .visitMethodInsn(eq(Opcodes.INVOKEVIRTUAL), any(), any(), any(), eq(false));
    return visitor;
  }

  /** Simulates the JVM operand stack for the instructions emitted by {@link CallSiteUtils}. */
  private static final class StackSimulator {

    private static final Pattern UNBOXING_METHOD =
        Pattern.compile("(?:char|boolean|byte|short|int|long|float|double)Value");

    private final List<StackObject> stack;

    StackSimulator(List<StackObject> stack) {
      this.stack = stack;
    }

    private StackObject peek(int depth) {
      return stack.get(stack.size() - depth);
    }

    private StackObject pop() {
      return stack.remove(stack.size() - 1);
    }

    private void handleDup() {
      StackObject last = peek(1);
      if (!isCategoryOne(last)) {
        throw new IllegalArgumentException();
      }
      stack.add(stack.size() - 1, last);
    }

    private void handleDupX1() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      if (!isCategoryOne(value1) || !isCategoryOne(value2)) {
        throw new IllegalArgumentException();
      }
      stack.add(stack.size() - 2, value1);
    }

    private void handleDupX2() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      StackObject value3 = peek(3);
      boolean valid = false;
      if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3)) {
        valid = true;
      } else if (isCategoryOne(value1) && isCategoryTwo(value2, value3)) {
        valid = true;
      }
      if (!valid) {
        throw new IllegalArgumentException();
      }
      stack.add(stack.size() - 3, value1);
    }

    private void handleDup2() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      boolean valid = false;
      if (isCategoryOne(value1) && isCategoryOne(value2)) {
        valid = true;
      } else if (isCategoryTwo(value1, value2)) {
        valid = true;
      }
      if (!valid) {
        throw new IllegalArgumentException();
      }
      stack.add(stack.size() - 2, value2);
      stack.add(stack.size() - 2, value1);
    }

    private void handleDup2X1() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      StackObject value3 = peek(3);
      boolean valid = false;
      if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3)) {
        valid = true;
      } else if (isCategoryTwo(value1, value2) && isCategoryOne(value3)) {
        valid = true;
      }
      if (!valid) {
        throw new IllegalArgumentException();
      }
      stack.add(stack.size() - 3, value2);
      stack.add(stack.size() - 3, value1);
    }

    private void handleDup2X2() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      StackObject value3 = peek(3);
      StackObject value4 = peek(4);
      boolean valid = false;
      if (isCategoryOne(value1)
          && isCategoryOne(value2)
          && isCategoryOne(value3)
          && isCategoryOne(value4)) {
        valid = true;
      } else if (isCategoryTwo(value1, value2) && isCategoryOne(value3) && isCategoryOne(value4)) {
        valid = true;
      } else if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryTwo(value3, value4)) {
        valid = true;
      } else if (isCategoryTwo(value1, value2) && isCategoryTwo(value3, value4)) {
        valid = true;
      }
      if (!valid) {
        throw new IllegalArgumentException();
      }
      stack.add(stack.size() - 4, value2);
      stack.add(stack.size() - 4, value1);
    }

    private void handlePop() {
      StackObject last = peek(1);
      if (!isCategoryOne(last)) {
        throw new IllegalArgumentException();
      }
      pop();
    }

    private void handlePop2() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      boolean valid = false;
      if (isCategoryOne(value1) && isCategoryOne(value2)) {
        valid = true;
      } else if (isCategoryTwo(value1, value2)) {
        valid = true;
      }
      if (!valid) {
        throw new IllegalArgumentException();
      }
      pop();
      pop();
    }

    private void handleSwap() {
      StackObject value1 = peek(1);
      StackObject value2 = peek(2);
      if (!isCategoryOne(value1) || !isCategoryOne(value2)) {
        throw new IllegalArgumentException();
      }
      pop();
      pop();
      stack.add(value1);
      stack.add(value2);
    }

    private void handleNewArray() {
      StackObject size = pop();
      assertEquals(INT_TYPE, size.type);
      stack.add(forObject(new Object[size.intValue]));
    }

    private void handleArrayStore() {
      StackObject target = pop();
      if (target.isCategoryTwo()) {
        pop();
      }
      StackObject indexStack = pop();
      StackObject arrayStack = pop();

      assertEquals(Type.OBJECT, target.type.getSort());
      assertEquals(INT_TYPE, indexStack.type);
      assertEquals(Type.getType(Object[].class), arrayStack.type);

      Object[] array = (Object[]) arrayStack.objectValue;
      array[indexStack.intValue] = target;
    }

    private void handleArrayLoad() {
      StackObject indexStack = pop();
      StackObject arrayStack = pop();

      assertEquals(INT_TYPE, indexStack.type);
      assertEquals(Type.getType(Object[].class), arrayStack.type);

      Object[] array = (Object[]) arrayStack.objectValue;
      StackObject item = (StackObject) array[indexStack.intValue];

      assertEquals(Type.OBJECT, item.type.getSort());

      item.addToStack(stack);
    }

    private void handleCheckCast(String typeDescriptor) {
      StackObject item = peek(1);
      assertEquals(typeDescriptor, item.type.getInternalName());
    }

    private void handleBoxing(String typeDescriptor) {
      StackObject item = pop();
      if (item.isCategoryTwo()) {
        pop();
      }
      StackObject boxed;
      switch (item.type.getSort()) {
        case Type.BOOLEAN:
          assertEquals("java/lang/Boolean", typeDescriptor);
          boxed = forObject(Boolean.valueOf(item.booleanValue));
          break;
        case Type.CHAR:
          assertEquals("java/lang/Character", typeDescriptor);
          boxed = forObject(Character.valueOf(item.charValue));
          break;
        case Type.BYTE:
          assertEquals("java/lang/Byte", typeDescriptor);
          boxed = forObject(Byte.valueOf(item.byteValue));
          break;
        case Type.SHORT:
          assertEquals("java/lang/Short", typeDescriptor);
          boxed = forObject(Short.valueOf(item.shortValue));
          break;
        case Type.INT:
          assertEquals("java/lang/Integer", typeDescriptor);
          boxed = forObject(Integer.valueOf(item.intValue));
          break;
        case Type.FLOAT:
          assertEquals("java/lang/Float", typeDescriptor);
          boxed = forObject(Float.valueOf(item.floatValue));
          break;
        case Type.LONG:
          assertEquals("java/lang/Long", typeDescriptor);
          boxed = forObject(Long.valueOf(item.longValue));
          break;
        case Type.DOUBLE:
          assertEquals("java/lang/Double", typeDescriptor);
          boxed = forObject(Double.valueOf(item.doubleValue));
          break;
        default:
          throw new IllegalArgumentException();
      }
      boxed.addToStack(stack);
    }

    private void handleUnBoxing(String typeDescriptor, String method) {
      if (!UNBOXING_METHOD.matcher(method).matches()) {
        throw new UnsupportedOperationException();
      }
      StackObject item = pop();
      if (item.isCategoryTwo()) {
        pop();
      }
      String internalName = item.type.getInternalName();
      assertEquals(internalName, typeDescriptor);
      StackObject unboxed;
      switch (internalName) {
        case "java/lang/Boolean":
          assertEquals("booleanValue", method);
          unboxed = forBoolean((Boolean) item.objectValue);
          break;
        case "java/lang/Character":
          assertEquals("charValue", method);
          unboxed = forChar((Character) item.objectValue);
          break;
        case "java/lang/Byte":
          assertEquals("byteValue", method);
          unboxed = forByte((Byte) item.objectValue);
          break;
        case "java/lang/Short":
          assertEquals("shortValue", method);
          unboxed = forShort((Short) item.objectValue);
          break;
        case "java/lang/Integer":
          assertEquals("intValue", method);
          unboxed = forInt((Integer) item.objectValue);
          break;
        case "java/lang/Float":
          assertEquals("floatValue", method);
          unboxed = forFloat((Float) item.objectValue);
          break;
        case "java/lang/Long":
          assertEquals("longValue", method);
          unboxed = forLong((Long) item.objectValue);
          break;
        case "java/lang/Double":
          assertEquals("doubleValue", method);
          unboxed = forDouble((Double) item.objectValue);
          break;
        default:
          throw new IllegalArgumentException();
      }
      unboxed.addToStack(stack);
    }
  }

  /** A value on the simulated operand stack; category II values take two stack slots. */
  public static final class StackObject {
    private boolean booleanValue;
    private byte byteValue;
    private char charValue;
    private short shortValue;
    private int intValue;
    private long longValue;
    private float floatValue;
    private double doubleValue;
    private Object objectValue;
    private final Type type;

    private StackObject(Type type) {
      this.type = type;
    }

    boolean isCategoryTwo() {
      return type.equals(LONG_TYPE) || type.equals(DOUBLE_TYPE);
    }

    void addToStack(List<StackObject> stack) {
      stack.add(this);
      if (isCategoryTwo()) {
        stack.add(this);
      }
    }

    static StackObject forBoolean(boolean value) {
      StackObject result = new StackObject(BOOLEAN_TYPE);
      result.booleanValue = value;
      return result;
    }

    static StackObject forChar(char value) {
      StackObject result = new StackObject(CHAR_TYPE);
      result.charValue = value;
      return result;
    }

    static StackObject forByte(byte value) {
      StackObject result = new StackObject(BYTE_TYPE);
      result.byteValue = value;
      return result;
    }

    static StackObject forShort(short value) {
      StackObject result = new StackObject(SHORT_TYPE);
      result.shortValue = value;
      return result;
    }

    static StackObject forInt(int value) {
      StackObject result = new StackObject(INT_TYPE);
      result.intValue = value;
      return result;
    }

    static StackObject forLong(long value) {
      StackObject result = new StackObject(LONG_TYPE);
      result.longValue = value;
      return result;
    }

    static StackObject forFloat(float value) {
      StackObject result = new StackObject(FLOAT_TYPE);
      result.floatValue = value;
      return result;
    }

    static StackObject forDouble(double value) {
      StackObject result = new StackObject(DOUBLE_TYPE);
      result.doubleValue = value;
      return result;
    }

    static StackObject forObject(Object value) {
      StackObject result = new StackObject(Type.getType(value.getClass()));
      result.objectValue = value;
      return result;
    }

    Object getValue() {
      switch (type.getSort()) {
        case Type.BOOLEAN:
          return booleanValue;
        case Type.CHAR:
          return charValue;
        case Type.BYTE:
          return byteValue;
        case Type.SHORT:
          return shortValue;
        case Type.INT:
          return intValue;
        case Type.FLOAT:
          return floatValue;
        case Type.LONG:
          return longValue;
        case Type.DOUBLE:
          return doubleValue;
        default:
          return objectValue;
      }
    }

    @Override
    public String toString() {
      Object value = getValue();
      return "Stack: " + (value instanceof Object[] ? Arrays.toString((Object[]) value) : value);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof StackObject)) {
        return false;
      }
      StackObject otherStack = (StackObject) other;
      if (!type.equals(otherStack.type)) {
        return false;
      }
      return Objects.deepEquals(getValue(), otherStack.getValue());
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, getValue());
    }
  }
}
