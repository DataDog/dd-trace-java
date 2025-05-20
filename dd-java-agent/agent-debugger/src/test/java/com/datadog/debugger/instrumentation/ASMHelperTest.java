package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.createLocalVarNodes;
import static com.datadog.debugger.instrumentation.ASMHelper.ensureSafeClassLoad;
import static com.datadog.debugger.instrumentation.ASMHelper.sortLocalVariables;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

public class ASMHelperTest {

  public static final LocalVariableNode THIS =
      createLocalVar(null, Types.OBJECT_TYPE.getDescriptor(), 0);

  @Test
  public void ensureSafeLoadClass() {
    IllegalArgumentException illegalArgumentException =
        assertThrows(IllegalArgumentException.class, () -> ensureSafeClassLoad("", null, null));
    assertEquals(
        "Cannot ensure loading class:  safely as current class being transformed is not provided (null)",
        illegalArgumentException.getMessage());
    illegalArgumentException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ensureSafeClassLoad(
                    "com.datadog.debugger.MyClass", "com.datadog.debugger.MyClass", null));
    assertEquals(
        "Cannot load class com.datadog.debugger.MyClass as this is the class being currently transformed",
        illegalArgumentException.getMessage());
    Class<?> clazz =
        ensureSafeClassLoad(
            ASMHelperTest.class.getTypeName(), "", ASMHelperTest.class.getClassLoader());
    assertEquals(ASMHelperTest.class, clazz);
  }

  @Test
  public void isStoreCompatibleType() {
    assertTrue(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.INT_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.SHORT_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.BYTE_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.CHAR_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.BOOLEAN_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.LONG_TYPE, Type.LONG_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.FLOAT_TYPE, Type.FLOAT_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.DOUBLE_TYPE, Type.DOUBLE_TYPE));
    assertTrue(ASMHelper.isStoreCompatibleType(Types.OBJECT_TYPE, Type.getType(Object.class)));
    assertTrue(ASMHelper.isStoreCompatibleType(Type.getType(Object.class), Types.OBJECT_TYPE));
    assertTrue(
        ASMHelper.isStoreCompatibleType(Type.getType(Object.class), Type.getType(String.class)));
    assertTrue(
        ASMHelper.isStoreCompatibleType(Type.getType(String.class), Type.getType(Object.class)));
    assertTrue(
        ASMHelper.isStoreCompatibleType(Type.getType(String.class), Type.getType(String.class)));
    assertFalse(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.LONG_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.FLOAT_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Type.DOUBLE_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Type.INT_TYPE, Types.OBJECT_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Types.OBJECT_TYPE, Type.INT_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Types.OBJECT_TYPE, Type.LONG_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Types.OBJECT_TYPE, Type.FLOAT_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Types.OBJECT_TYPE, Type.DOUBLE_TYPE));
    assertFalse(ASMHelper.isStoreCompatibleType(Type.getType(String.class), Type.INT_TYPE));
  }

  @Test
  public void adjustLocalVarsBasedOnArgs_empty() {
    LocalVariableNode[] emptyLocalVariables = new LocalVariableNode[0];
    Type[] emptyArgs = new Type[0];
    ASMHelper.adjustLocalVarsBasedOnArgs(
        false, emptyLocalVariables, emptyArgs, Collections.emptyList());
    ASMHelper.adjustLocalVarsBasedOnArgs(
        true, emptyLocalVariables, emptyArgs, Collections.emptyList());
  }

  @Test
  public void adjustLocalVarsBasedOnArgs() {
    doAdjustLocalVarsBasedOnArgs(
        true, asList(Type.INT_TYPE), asList(createLocalVar("a", "I", 0)), asList("a"));
    doAdjustLocalVarsBasedOnArgs(
        false, asList(Type.INT_TYPE), asList(THIS, createLocalVar("a", "I", 1)), asList("a"));
    doAdjustLocalVarsBasedOnArgs(
        true,
        asList(Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
        asList(
            createLocalVar("c", "I", 2), createLocalVar("b", "I", 1), createLocalVar("a", "I", 0)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        false,
        asList(Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
        asList(
            THIS,
            createLocalVar("c", "I", 3),
            createLocalVar("b", "I", 2),
            createLocalVar("a", "I", 1)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        true,
        asList(Type.LONG_TYPE, Type.LONG_TYPE, Type.LONG_TYPE),
        asList(
            createLocalVar("c", "J", 4), createLocalVar("b", "J", 2), createLocalVar("a", "J", 0)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        false,
        asList(Type.LONG_TYPE, Type.LONG_TYPE, Type.LONG_TYPE),
        asList(
            THIS,
            createLocalVar("c", "J", 5),
            createLocalVar("b", "J", 3),
            createLocalVar("a", "J", 1)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        true,
        asList(Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
        asList(
            createLocalVar("c", "I", 6), createLocalVar("b", "I", 5), createLocalVar("a", "I", 4)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        false,
        asList(Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE),
        asList(
            THIS,
            createLocalVar("c", "I", 6),
            createLocalVar("b", "I", 5),
            createLocalVar("a", "I", 4)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        true,
        asList(Type.LONG_TYPE, Type.LONG_TYPE, Type.LONG_TYPE),
        asList(
            createLocalVar("c", "J", 10), createLocalVar("b", "J", 8), createLocalVar("a", "J", 6)),
        asList("a", "b", "c"));
    doAdjustLocalVarsBasedOnArgs(
        false,
        asList(Type.LONG_TYPE, Type.LONG_TYPE, Type.LONG_TYPE),
        asList(
            THIS,
            createLocalVar("c", "J", 10),
            createLocalVar("b", "J", 8),
            createLocalVar("a", "J", 6)),
        asList("a", "b", "c"));
  }

  private void doAdjustLocalVarsBasedOnArgs(
      boolean isStatic,
      List<Type> argTypes,
      List<LocalVariableNode> localVarNodes,
      List<String> expectedLocalVarNames) {
    Type[] args = argTypes.toArray(new Type[0]);
    List<LocalVariableNode> sortedLocalVariables = sortLocalVariables(localVarNodes);
    LocalVariableNode[] localVars = createLocalVarNodes(sortedLocalVariables);
    ASMHelper.adjustLocalVarsBasedOnArgs(isStatic, localVars, args, sortedLocalVariables);
    int idx = isStatic ? 0 : 1;
    for (String name : expectedLocalVarNames) {
      assertEquals(name, localVars[idx].name);
      idx += Type.getType(localVars[idx].desc).getSize();
    }
  }

  private static LocalVariableNode createLocalVar(String name, String desc, int index) {
    return new LocalVariableNode(name, desc, null, null, null, index);
  }

  @Test
  void getLineNumbers() {
    assertEquals(Collections.emptyList(), ASMHelper.getLineNumbers(null));
    MethodNode methodNode = new MethodNode();
    assertEquals(Collections.emptyList(), ASMHelper.getLineNumbers(methodNode));
    methodNode.instructions.add(new LineNumberNode(40, null));
    methodNode.instructions.add(new LineNumberNode(2, null));
    methodNode.instructions.add(new LineNumberNode(82, null));
    methodNode.instructions.add(new LabelNode());
    List<Integer> lineNumbers = ASMHelper.getLineNumbers(methodNode);
    assertEquals(3, lineNumbers.size());
    assertEquals(40, lineNumbers.get(0).intValue());
    assertEquals(2, lineNumbers.get(1).intValue());
    assertEquals(82, lineNumbers.get(2).intValue());
  }
}
