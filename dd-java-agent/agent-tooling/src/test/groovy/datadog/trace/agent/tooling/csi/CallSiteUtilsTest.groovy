package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteUtils
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.*
import static datadog.trace.agent.tooling.csi.CallSiteUtilsTest.StackObject.*
import static net.bytebuddy.jar.asm.Type.*

@CompileDynamic
class CallSiteUtilsTest extends DDSpecification {

  void 'test push int value "#value" onto stack'(final int value, final int opcode) {
    setup:
    final visitor = Mock(MethodVisitor)

    when:
    CallSiteUtils.pushInteger(visitor, value)

    then:
    if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
      1 * visitor.visitInsn(opcode)
    } else {
      if (opcode == Opcodes.LDC) {
        1 * visitor.visitLdcInsn(value)
      } else {
        1 * visitor.visitIntInsn(opcode, value)
      }
    }
    0 * _

    where:
    value               | opcode
    -1                  | Opcodes.ICONST_M1
    0                   | Opcodes.ICONST_0
    1                   | Opcodes.ICONST_1
    2                   | Opcodes.ICONST_2
    3                   | Opcodes.ICONST_3
    4                   | Opcodes.ICONST_4
    5                   | Opcodes.ICONST_5
    Short.MIN_VALUE - 1 | Opcodes.LDC
    Short.MIN_VALUE     | Opcodes.SIPUSH
    Short.MAX_VALUE + 1 | Opcodes.LDC
    Short.MAX_VALUE     | Opcodes.SIPUSH
    Byte.MIN_VALUE - 1  | Opcodes.SIPUSH
    Byte.MIN_VALUE      | Opcodes.BIPUSH
    Byte.MAX_VALUE + 1  | Opcodes.SIPUSH
    Byte.MAX_VALUE      | Opcodes.BIPUSH
  }

  void 'test swap [..., #secondToLastSize, #lastSize]'(final int secondToLastSize,
    final int lastSize,
    final List<Integer> opcodes) {
    setup:
    final visitor = Mock(MethodVisitor)

    when:
    CallSiteUtils.swap(visitor, secondToLastSize, lastSize)

    then:
    opcodes.each {
      1 * visitor.visitInsn(it)
    }
    0 * _

    where:
    secondToLastSize | lastSize | opcodes
    1                | 1        | [Opcodes.SWAP]
    2                | 2        | [Opcodes.DUP2_X2, Opcodes.POP2]
    1                | 2        | [Opcodes.DUP2_X1, Opcodes.POP2]
    2                | 1        | [Opcodes.DUP_X2, Opcodes.POP]
  }

  void 'test stack clone of #items'(final List<StackObject> items, final List<StackObject> expected) {
    setup:
    final stack = buildStack(items)
    final visitor = mockMethodVisitor(stack)

    when:
    CallSiteUtils.dup(visitor, items.collect { it.type } as Type[], COPY)

    then:
    final result = fromStack(stack)
    result == expected

    where:
    items                                                                         | expected
    [forInt(1)]                                                                   | items + items
    [forInt(1), forInt(2)]                                                        | items + items
    [forLong(1L)]                                                                 | items + items
    [forLong(1L), forLong(2L)]                                                    | items + items
    [forInt(1), forLong(2L)]                                                      | items + items
    [forInt(1), forInt(2), forLong(3L)]                                           | items + items
    [forInt(1), forInt(2), forInt(3), forLong(4L)]                                | items + items
    [forObject('PI = '), forDouble(3.14D), forChar((char) '?'), forBoolean(true)] | items + items
  }

  void 'test dup with explicit indices for #items indices #indices'() {
    setup:
    final stack = buildStack(items)
    final visitor = mockMethodVisitor(stack)

    when:
    CallSiteUtils.dup(visitor, items.collect { it.type } as Type[], indices as int[])

    then:
    final result = fromStack(stack)
    result == items + expectedExtra

    where:
    items                                          | indices   | expectedExtra
    [forInt(1), forByte(2 as byte)]                | [0]       | forInt(1) // 12|1
    [forInt(1), forLong(2L)]                       | [0]       | forInt(1) // 12L|1
    [forLong(1L), forInt(2)]                       | [0]       | forLong(1L) // 1L2|1L
    [forLong(1L), forLong(2L)]                     | [0]       | forLong(1L) // 1L2|1L
    [forInt(1), forInt(2), forInt(3)]              | [0]       | forInt(1) // 123|1
    [forInt(1), forInt(2), forLong(3L)]            | [0]       | forInt(1) // 123L|1
    [forLong(1L), forInt(2), forInt(3)]            | [0]       | forLong(1L) // 1L23|1L
    [forInt(1), forInt(2), forInt(3)]              | [0, 1]    | [forInt(1), forInt(2)] // 123|12
    [forInt(1), forInt(2), forLong(3L)]            | [0, 1]    | [forInt(1), forInt(2)] // 123L|12
    [forLong(1L), forInt(2), forInt(3)]            | [0, 1]    | [forLong(1L), forInt(2)] // 1L23|1L2
    [forInt(1), forInt(2), forInt(3)]              | [0, 2]    | [forInt(1), forInt(3)] // 123|13
    [forInt(1), forInt(2), forLong(3L)]            | [0, 2]    | [forInt(1), forLong(3L)] // 123L|13L
    [forLong(1), forInt(2), forInt(3)]             | [0, 2]    | [forLong(1L), forInt(3)] // 1L23|1L3
    [forInt(1), forInt(2), forInt(3), forInt(4)]   | [0]       | forInt(1) // 1234|1
    [forInt(1), forInt(2), forInt(3), forInt(4)]   | [0, 1]    | [forInt(1), forInt(2)] // 1234|12
    [forInt(1), forInt(2), forInt(3), forInt(4)]   | [0, 2]    | [forInt(1), forInt(3)] // 1234|13
    [forInt(1), forInt(2), forInt(3), forInt(4)]   | [0, 3]    | [forInt(1), forInt(4)] // 1234|14
    [forInt(1), forInt(2), forInt(3), forInt(4)]   | [0, 1, 3] | [forInt(1), forInt(2), forInt(4)] // 1234|124
    [forInt(1), forInt(2), forInt(3), forInt(4)]   | [0, 2, 3] | [forInt(1), forInt(3), forInt(4)] // 1234|134
    // fallback cases
    [forInt(1), forLong(2L), forInt(3), forInt(4)] | [0, 2, 3] | [forInt(1), forInt(3), forInt(4)]
    [forInt(1), forInt(2), forInt(3)]              | [2, 2, 0] | [forInt(3), forInt(3), forInt(1)]
  }

  void 'test stack dup with array before of #items'(final List<StackObject> items, final List<StackObject> expected) {
    setup:
    final stack = buildStack(items)
    final visitor = mockMethodVisitor(stack)

    when:
    CallSiteUtils.dup(visitor, items.collect { it.type } as Type[], PREPEND_ARRAY)

    then: 'the first element of the stack should be an array with the parameters'
    final arrayFromStack = stack.remove(0)
    arrayFromStack.type.descriptor == "[Ljava/lang/Object;"
    final array = (arrayFromStack.value as Object[]).toList() as List<StackObject>
    [array, expected].transpose().each { arrayItem, expectedItem ->
      assert arrayItem.value == expectedItem.value // some of the items might be boxed so be careful
    }

    then: 'the rest of the array should contain the expected values'
    final result = fromStack(stack)
    result == expected

    where:
    items                                                                         | expected
    [forInt(1)]                                                                   | items
    [forInt(1), forInt(2)]                                                        | items
    [forLong(1L)]                                                                 | items
    [forLong(1L), forLong(2L)]                                                    | items
    [forInt(1), forLong(2L)]                                                      | items
    [forInt(1), forInt(2), forLong(3L)]                                           | items
    [forInt(1), forInt(2), forInt(3), forLong(4L)]                                | items
    [forObject('PI = '), forDouble(3.14D), forChar((char) '?'), forBoolean(true)] | items
  }

  void 'test stack dup with array after of #items'(final List<StackObject> items, final List<StackObject> expected) {
    setup:
    final stack = buildStack(items)
    final visitor = mockMethodVisitor(stack)

    when:
    CallSiteUtils.dup(visitor, items.collect { it.type } as Type[], APPEND_ARRAY)

    then: 'the last element of the stack should be an array with the parameters'
    final arrayFromStack = stack.remove(stack.size() - 1)
    arrayFromStack.type.descriptor == "[Ljava/lang/Object;"
    final array = (arrayFromStack.value as Object[]).toList() as List<StackObject>
    [array, expected].transpose().each { arrayItem, expectedItem ->
      assert arrayItem.value == expectedItem.value // some of the items might be boxed so be careful
    }

    then: 'the rest of the array should contain the expected values'
    final result = fromStack(stack)
    result == expected

    where:
    items                                                                         | expected
    [forInt(1)]                                                                   | items
    [forInt(1), forInt(2)]                                                        | items
    [forLong(1L)]                                                                 | items
    [forLong(1L), forLong(2L)]                                                    | items
    [forInt(1), forLong(2L)]                                                      | items
    [forInt(1), forInt(2), forLong(3L)]                                           | items
    [forInt(1), forInt(2), forInt(3), forLong(4L)]                                | items
    [forObject('PI = '), forDouble(3.14D), forChar((char) '?'), forBoolean(true)] | items
  }

  void 'test stack dup with array before ctor of #items'() {
    setup:
    final stack = buildStack(items)
    final visitor = mockMethodVisitor(stack)

    when:
    CallSiteUtils.dup(visitor, expected*.type as Type[], PREPEND_ARRAY_CTOR)

    then: 'the first element of the stack should be an array with the parameters'
    final arrayFromStack = stack.remove(0)
    arrayFromStack.type.descriptor == '[Ljava/lang/Object;'
    final array = (arrayFromStack.value as Object[]).toList() as List<StackObject>
    [array, expected].transpose().each { arrayItem, expectedItem ->
      assert arrayItem.value == expectedItem.value // some of the items might be boxed so be careful
    }

    then: 'the rest of the stack should contain the original values'
    final result = fromStack(stack)
    result == items

    where:
    items                                                                                                             | expected
    [forObject('NEW'), forObject('DUP'), forInt(1)]                                                                   | items.subList(2, items.size())
    [forObject('NEW'), forObject('DUP'), forInt(1), forInt(2)]                                                        | items.subList(2, items.size())
    [forObject('NEW'), forObject('DUP'), forLong(1L)]                                                                 | items.subList(2, items.size())
    [forObject('NEW'), forObject('DUP'), forLong(1L), forLong(2L)]                                                    | items.subList(2, items.size())
    [forObject('NEW'), forObject('DUP'), forInt(1), forLong(2L)]                                                      | items.subList(2, items.size())
    [forObject('NEW'), forObject('DUP'), forInt(1), forInt(2), forLong(3L)]                                           | items.subList(2, items.size())
    [forObject('NEW'), forObject('DUP'), forInt(1), forInt(2), forInt(3), forLong(4L)]                                | items.subList(2, items.size())
    [
      forObject('NEW'),
      forObject('DUP'),
      forObject('PI = '),
      forDouble(3.14D),
      forChar((char) '?'),
      forBoolean(true)
    ] | items.subList(2, items.size())
  }

  private MethodVisitor mockMethodVisitor(final List<StackObject> stack) {
    return Mock(MethodVisitor) {
      visitInsn(Opcodes.DUP) >> { handleDUP(stack) }
      visitInsn(Opcodes.DUP_X1) >> { handleDUP_X1(stack) }
      visitInsn(Opcodes.DUP_X2) >> { handleDUP_X2(stack) }
      visitInsn(Opcodes.DUP2) >> { handleDUP2(stack) }
      visitInsn(Opcodes.DUP2_X1) >> { handleDUP2_X1(stack) }
      visitInsn(Opcodes.DUP2_X2) >> { handleDUP2_X2(stack) }
      visitInsn(Opcodes.POP) >> { handlePOP(stack) }
      visitInsn(Opcodes.POP2) >> { handlePOP2(stack) }
      visitInsn(Opcodes.SWAP) >> { handleSWAP(stack) }
      visitInsn(Opcodes.ICONST_M1) >> { stack.add(forInt(-1)) }
      visitInsn(Opcodes.ICONST_0) >> { stack.add(forInt(0)) }
      visitInsn(Opcodes.ICONST_1) >> { stack.add(forInt(1)) }
      visitInsn(Opcodes.ICONST_2) >> { stack.add(forInt(2)) }
      visitInsn(Opcodes.ICONST_3) >> { stack.add(forInt(3)) }
      visitInsn(Opcodes.ICONST_4) >> { stack.add(forInt(4)) }
      visitInsn(Opcodes.ICONST_5) >> { stack.add(forInt(5)) }
      visitIntInsn(Opcodes.BIPUSH, _) >> { stack.add(forInt(it[1] as int)) }
      visitTypeInsn(Opcodes.ANEWARRAY, 'java/lang/Object') >> { handleNewArray(stack) }
      visitInsn(Opcodes.AASTORE) >> { handleArrayStore(stack) }
      visitInsn(Opcodes.AALOAD) >> { handleArrayLoad(stack) }
      visitTypeInsn(Opcodes.CHECKCAST, _) >> { handleCheckCast(stack, it[1] as String) }
      visitMethodInsn(Opcodes.INVOKESTATIC, _, 'valueOf', _, false) >> { handleBoxing(stack, it[1] as String) }
      visitMethodInsn(Opcodes.INVOKEVIRTUAL, _, _, _, false) >> {
        final method = it[2] as String
        if (method ==~ /(:?char|boolean|byte|short|int|long|float|double)Value/) {
          handleUnBoxing(stack, it[1] as String, method)
        } else {
          throw new UnsupportedOperationException()
        }
      }
      _ >> { throw new IllegalArgumentException('Not yet implemented') }
    }
  }

  private static void handleDUP(final List<StackObject> stack) {
    final last = stack.get(stack.size() - 1)
    if (!isCategoryOne(last)) {
      throw new IllegalArgumentException()
    }
    stack.add(stack.size() - 1, last)
  }

  private static void handleDUP_X1(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    if (!isCategoryOne(value1) || !isCategoryOne(value2)) {
      throw new IllegalArgumentException()
    }
    stack.add(stack.size() - 2, value1)
  }

  private static void handleDUP_X2(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    final value3 = stack.get(stack.size() - 3)
    boolean valid = false
    if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3)) {
      valid = true
    } else if (isCategoryOne(value1) && isCategoryTwo(value2, value3)) {
      valid = true
    }
    if (!valid) {
      throw new IllegalArgumentException()
    }
    stack.add(stack.size() - 3, value1)
  }

  private static void handleDUP2(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    boolean valid = false
    if (isCategoryOne(value1) && isCategoryOne(value2)) {
      valid = true
    } else if (isCategoryTwo(value1, value2)) {
      valid = true
    }
    if (!valid) {
      throw new IllegalArgumentException()
    }
    stack.add(stack.size() - 2, value2)
    stack.add(stack.size() - 2, value1)
  }

  private static void handleDUP2_X1(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    final value3 = stack.get(stack.size() - 3)
    boolean valid = false
    if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3)) {
      valid = true
    } else if (isCategoryTwo(value1, value2) && isCategoryOne(value3)) {
      valid = true
    }
    if (!valid) {
      throw new IllegalArgumentException()
    }
    stack.add(stack.size() - 3, value2)
    stack.add(stack.size() - 3, value1)
  }

  private static void handleDUP2_X2(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    final value3 = stack.get(stack.size() - 3)
    final value4 = stack.get(stack.size() - 4)
    boolean valid = false
    if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryOne(value3) && isCategoryOne(value4)) {
      valid = true
    } else if (isCategoryTwo(value1, value2) && isCategoryOne(value3) && isCategoryOne(value4)) {
      valid = true
    } else if (isCategoryOne(value1) && isCategoryOne(value2) && isCategoryTwo(value3, value4)) {
      valid = true
    } else if (isCategoryTwo(value1, value2) && isCategoryTwo(value3, value4)) {
      valid = true
    }
    if (!valid) {
      throw new IllegalArgumentException()
    }
    stack.add(stack.size() - 4, value2)
    stack.add(stack.size() - 4, value1)
  }

  private static void handlePOP(final List<StackObject> stack) {
    final last = stack.get(stack.size() - 1)
    if (!isCategoryOne(last)) {
      throw new IllegalArgumentException()
    }
    stack.remove(stack.size() - 1)
  }

  private static void handlePOP2(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    boolean valid = false
    if (isCategoryOne(value1) && isCategoryOne(value2)) {
      valid = true
    } else if (isCategoryTwo(value1, value2)) {
      valid = true
    }
    if (!valid) {
      throw new IllegalArgumentException()
    }
    stack.remove(stack.size() - 1)
    stack.remove(stack.size() - 1)
  }

  private static void handleSWAP(final List<StackObject> stack) {
    final value1 = stack.get(stack.size() - 1)
    final value2 = stack.get(stack.size() - 2)
    boolean valid = false
    if (isCategoryOne(value1) && isCategoryOne(value2)) {
      valid = true
    }
    if (!valid) {
      throw new IllegalArgumentException()
    }
    stack.remove(stack.size() - 1)
    stack.remove(stack.size() - 1)
    stack.add(value1)
    stack.add(value2)
  }

  private static void handleNewArray(final List<StackObject> stack) {
    final size = stack.remove(stack.size() - 1)
    assert size.type == INT_TYPE
    stack.add(forObject(new Object[size.intValue]))
  }

  private static void handleArrayStore(final List<StackObject> stack) {
    final target = stack.remove(stack.size() - 1)
    if (target.categoryTwo) {
      stack.remove(stack.size() - 1)
    }
    final indexStack = stack.remove(stack.size() - 1)
    final arrayStack = stack.remove(stack.size() - 1)

    assert target.type.sort == Type.OBJECT
    assert indexStack.type == INT_TYPE
    assert arrayStack.type == Type.getType(Object[].class)

    final index = indexStack.intValue
    final array = arrayStack.objectValue as Object[]
    array[index] = target
  }

  private static void handleArrayLoad(final List<StackObject> stack) {
    final indexStack = stack.remove(stack.size() - 1)
    final arrayStack = stack.remove(stack.size() - 1)

    assert indexStack.type == INT_TYPE
    assert arrayStack.type == Type.getType(Object[].class)

    final index = indexStack.intValue
    final array = arrayStack.objectValue as Object[]
    final item = array[index] as StackObject

    assert item.type.sort == Type.OBJECT

    item.addToStack(stack)
  }

  private static void handleCheckCast(final List<StackObject> stack, final String typeDescriptor) {
    final item = stack.get(stack.size() - 1)
    assert item.type.internalName == typeDescriptor
  }

  private static void handleBoxing(final List<StackObject> stack, final String typeDescriptor) {
    final item = stack.remove(stack.size() - 1)
    if (item.categoryTwo) {
      stack.remove(stack.size() - 1)
    }
    StackObject boxed
    switch (item.type.sort) {
      case Type.BOOLEAN:
        assert typeDescriptor == 'java/lang/Boolean'
        boxed = forObject(Boolean.valueOf(item.booleanValue))
        break
      case Type.CHAR:
        assert typeDescriptor == 'java/lang/Character'
        boxed = forObject(Character.valueOf(item.charValue))
        break
      case Type.BYTE:
        assert typeDescriptor == 'java/lang/Byte'
        boxed = forObject(Byte.valueOf(item.byteValue))
        break
      case Type.SHORT:
        assert typeDescriptor == 'java/lang/Short'
        boxed = forObject(Short.valueOf(item.shortValue))
        break
      case Type.INT:
        assert typeDescriptor == 'java/lang/Integer'
        boxed = forObject(Integer.valueOf(item.intValue))
        break
      case Type.FLOAT:
        assert typeDescriptor == 'java/lang/Float'
        boxed = forObject(Float.valueOf(item.floatValue))
        break
      case Type.LONG:
        assert typeDescriptor == 'java/lang/Long'
        boxed = forObject(Long.valueOf(item.longValue))
        break
      case Type.DOUBLE:
        assert typeDescriptor == 'java/lang/Double'
        boxed = forObject(Double.valueOf(item.doubleValue))
        break
      default:
        throw new IllegalArgumentException()
    }
    boxed.addToStack(stack)
  }

  private static void handleUnBoxing(final List<StackObject> stack, final String typeDescriptor, final String method) {
    final item = stack.remove(stack.size() - 1)
    if (item.categoryTwo) {
      stack.remove(stack.size() - 1)
    }
    StackObject unboxed
    if (item.type.internalName == 'java/lang/Boolean') {
      assert typeDescriptor == 'java/lang/Boolean'
      assert method == 'booleanValue'
      unboxed = forBoolean(((Boolean) item.objectValue).booleanValue())
    } else if (item.type.internalName == 'java/lang/Character') {
      assert typeDescriptor == 'java/lang/Character'
      assert method == 'charValue'
      unboxed = forChar(((Character) item.objectValue).charValue())
    } else if (item.type.internalName == 'java/lang/Byte') {
      assert typeDescriptor == 'java/lang/Byte'
      assert method == 'byteValue'
      unboxed = forByte(((Byte) item.objectValue).byteValue())
    } else if (item.type.internalName == 'java/lang/Short') {
      assert typeDescriptor == 'java/lang/Short'
      assert method == 'shortValue'
      unboxed = forShort(((Short) item.objectValue).shortValue())
    } else if (item.type.internalName == 'java/lang/Integer') {
      assert typeDescriptor == 'java/lang/Integer'
      assert method == 'intValue'
      unboxed = forInt(((Integer) item.objectValue).intValue())
    } else if (item.type.internalName == 'java/lang/Float') {
      assert typeDescriptor == 'java/lang/Float'
      assert method == 'floatValue'
      unboxed = forFloat(((Float) item.objectValue).floatValue())
    } else if (item.type.internalName == 'java/lang/Long') {
      assert typeDescriptor == 'java/lang/Long'
      assert method == 'longValue'
      unboxed = forLong(((Long) item.objectValue).longValue())
    } else if (item.type.internalName == 'java/lang/Double') {
      assert typeDescriptor == 'java/lang/Double'
      assert method == 'doubleValue'
      unboxed = forDouble(((Double) item.objectValue).doubleValue())
    } else {
      throw new IllegalArgumentException()
    }
    unboxed.addToStack(stack)
  }

  private static boolean isCategoryOne(final StackObject value) {
    return !value.categoryTwo
  }

  private static boolean isCategoryTwo(final StackObject value1, final StackObject value2) {
    return value1 == value2 && value1.categoryTwo
  }

  private static List<StackObject> buildStack(final List<StackObject> items) {
    final stack = [] as List<StackObject>
    items.each { value ->
      value.addToStack(stack)
    }
    return stack
  }

  private static List<StackObject> fromStack(final List<StackObject> list) {
    final result = [] as List<StackObject>
    for (def i = 0; i < list.size(); i++) {
      final stack = list.get(i)
      result.add(stack)
      if (stack.categoryTwo) {
        final next = list.get(++i)
        assert next == stack: "Category II objects take two places in the stack"
      }
    }
    return result
  }

  private static class StackObject {
    boolean booleanValue
    byte byteValue
    char charValue
    short shortValue
    int intValue
    long longValue
    float floatValue
    double doubleValue
    Object objectValue
    Type type

    boolean isCategoryTwo() {
      return type == LONG_TYPE || type == DOUBLE_TYPE
    }

    void addToStack(final List<StackObject> stack) {
      stack.add(this)
      if (isCategoryTwo()) {
        stack.add(this)
      }
    }

    static StackObject forBoolean(final boolean value) {
      return new StackObject(booleanValue: value, type: BOOLEAN_TYPE)
    }

    static StackObject forChar(final char value) {
      return new StackObject(charValue: value, type: CHAR_TYPE)
    }

    static StackObject forByte(final byte value) {
      return new StackObject(byteValue: value, type: BYTE_TYPE)
    }

    static StackObject forShort(final short value) {
      return new StackObject(shortValue: value, type: SHORT_TYPE)
    }

    static StackObject forInt(final int value) {
      return new StackObject(intValue: value, type: INT_TYPE)
    }

    static StackObject forLong(final long value) {
      return new StackObject(longValue: value, type: LONG_TYPE)
    }

    static StackObject forFloat(final float value) {
      return new StackObject(floatValue: value, type: FLOAT_TYPE)
    }

    static StackObject forDouble(final double value) {
      return new StackObject(doubleValue: value, type: DOUBLE_TYPE)
    }

    static StackObject forObject(final Object value) {
      return new StackObject(objectValue: value, type: Type.getType(value.getClass()))
    }

    Object getValue() {
      switch (type.sort) {
        case Type.BOOLEAN:
          return booleanValue
        case Type.CHAR:
          return charValue
        case Type.BYTE:
          return byteValue
        case Type.SHORT:
          return shortValue
        case Type.INT:
          return intValue
        case Type.FLOAT:
          return floatValue
        case Type.LONG:
          return longValue
        case Type.DOUBLE:
          return doubleValue
        default:
          return objectValue
      }
    }

    @Override
    String toString() {
      return "Stack: $value"
    }

    @Override
    boolean equals(final Object other) {
      if (!(other instanceof StackObject)) {
        return false
      }
      final otherStack = (StackObject) other
      if (type != otherStack.type) {
        return false
      }
      return value == otherStack.value
    }
  }
}
