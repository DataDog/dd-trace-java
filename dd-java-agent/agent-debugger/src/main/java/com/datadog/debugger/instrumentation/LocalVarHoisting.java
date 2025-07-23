package com.datadog.debugger.instrumentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalVarHoisting {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalVarHoisting.class);

  /**
   * Hoisting strategy interface to define how local variables should be hoisted. Implementations
   * can provide different hoisting strategies, such as safe or aggressive hoisting.
   */
  private interface HoistingStrategy {
    void hoist(
        MethodNode method,
        LocalVariableNode varNode,
        Set<Integer> forbiddenSlots,
        Map<Integer, Integer> countBySlot,
        Map<String, Integer> countByName,
        SlotInfo slotInfo,
        LabelNode methodEnterLabel,
        LabelNode methodEndLabel,
        Collection<LocalVariableNode> hoisted);
  }

  public static Collection<LocalVariableNode> processMethod(MethodNode method, int hoistingLevel) {
    Map<Integer, SlotInfo> slots = new HashMap<>();
    Set<Integer> forbiddenSlots = new HashSet<>();
    scanInstructions(method.instructions, slots, forbiddenSlots);
    Map<Integer, Integer> countBySlot = new HashMap<>();
    Map<String, Integer> countByName = new HashMap<>();
    scanLocalVariableTable(method.localVariables, countBySlot, countByName);
    LabelNode methodEnterLabel = new LabelNode();
    method.instructions.insert(methodEnterLabel);
    LabelNode methodEndLabel = new LabelNode();
    method.instructions.add(methodEndLabel);
    Collection<LocalVariableNode> hoisted = new ArrayList<>();
    HoistingStrategy hoistingStrategy = getHoistingStrategy(hoistingLevel);
    for (LocalVariableNode varNode : method.localVariables) {
      if (isParameter(method, varNode.index)) {
        // Skip parameters, they are not hoistable
        // LOGGER.debug("Variable: {} at index: {} is a parameter and cannot be hoisted.",
        // varNode.name, varNode.index);
        continue;
      }
      SlotInfo slotInfo = slots.get(varNode.index);
      if (slotInfo == null) {
        // If the slot is not defined, we can skip it
        LOGGER.debug("Variable: {} at index: {} has no slot info.", varNode.name, varNode.index);
        continue;
      }
      hoistingStrategy.hoist(
          method,
          varNode,
          forbiddenSlots,
          countBySlot,
          countByName,
          slotInfo,
          methodEnterLabel,
          methodEndLabel,
          hoisted);
    }
    return hoisted;
  }

  private static HoistingStrategy getHoistingStrategy(int hoistingLevel) {
    switch (hoistingLevel) {
      case 0:
        // No hoisting, return a no-op strategy
        return LocalVarHoisting::noopHoisting;
      case 1:
        return LocalVarHoisting::safeHoisting;
      case 2:
        return LocalVarHoisting::aggressiveHoisting;
      default:
        LOGGER.warn("Unknown hoisting level: {}", hoistingLevel);
        return LocalVarHoisting::noopHoisting;
    }
  }

  private static void noopHoisting(
      MethodNode method,
      LocalVariableNode varNode,
      Set<Integer> forbiddenSlots,
      Map<Integer, Integer> countBySlot,
      Map<String, Integer> countByName,
      SlotInfo slotInfo,
      LabelNode methodEnterLabel,
      LabelNode methodEndLabel,
      Collection<LocalVariableNode> hoisted) {}

  private static void safeHoisting(
      MethodNode method,
      LocalVariableNode varNode,
      Set<Integer> forbiddenSlots,
      Map<Integer, Integer> countBySlot,
      Map<String, Integer> countByName,
      SlotInfo slotInfo,
      LabelNode methodEnterLabel,
      LabelNode methodEndLabel,
      Collection<LocalVariableNode> hoisted) {
    if (forbiddenSlots.contains(varNode.index)) {
      // If the slot is forbidden, we can skip it
      LOGGER.debug(
          "Variable: {} at index: {} is in a forbidden slot.", varNode.name, varNode.index);
      return;
    }
    int countSlot = countBySlot.get(varNode.index);
    int countName = countByName.get(varNode.name);
    boolean isOnlyOneType = slotInfo.isOnlyOneType();
    boolean isSingleSlotType = slotInfo.isSingleSlotType();
    if (countSlot == 1 && countName == 1 && isOnlyOneType && isSingleSlotType) {
      // one local variable for this slot defined only for one type => safely hoistable
      LOGGER.debug("Variable: {} at index: {} is safely hoistable.", varNode.name, varNode.index);
      extendRange(varNode, methodEnterLabel, methodEndLabel);
      InsnList init = new InsnList();
      addStore0Insn(init, varNode, Type.getType(varNode.desc));
      method.instructions.insert(methodEnterLabel, init);
      hoisted.add(varNode);
    } else {
      // not safely hoistable, we can still try aggressive hoisting
      LOGGER.debug(
          "Variable: {} at index: {} is not safely hoistable, resons: "
              + "countSlot={}, countName={}, isOnlyOneType={}, isSingleSlotType={}",
          varNode.name,
          varNode.index,
          countSlot,
          countName,
          isOnlyOneType,
          isSingleSlotType);
    }
  }

  private static void aggressiveHoisting(
      MethodNode method,
      LocalVariableNode varNode,
      Set<Integer> forbiddenSlots,
      Map<Integer, Integer> countBySlot,
      Map<String, Integer> countByName,
      SlotInfo slotInfo,
      LabelNode methodEnterLabel,
      LabelNode methodEndLabel,
      Collection<LocalVariableNode> hoisted) {
    throw new RuntimeException("Aggressive hoisting not implemented yet.");
  }

  private static boolean isParameter(MethodNode method, int slot) {
    return slot < getParameterSlotCount(method);
  }

  private static int getParameterSlotCount(MethodNode method) {
    Type[] argTypes = Type.getArgumentTypes(method.desc);
    int count = 0;
    if ((method.access & Opcodes.ACC_STATIC) == 0) {
      count = 1; // 'this' parameter
    }
    for (Type type : argTypes) {
      count += type.getSize();
    }
    return count;
  }

  private static void addStore0Insn(
      InsnList insnList, LocalVariableNode localVar, Type localVarType) {
    switch (localVarType.getSort()) {
      case Type.BOOLEAN:
      case Type.CHAR:
      case Type.BYTE:
      case Type.SHORT:
      case Type.INT:
        insnList.add(new InsnNode(Opcodes.ICONST_0));
        break;
      case Type.LONG:
        insnList.add(new InsnNode(Opcodes.LCONST_0));
        break;
      case Type.FLOAT:
        insnList.add(new InsnNode(Opcodes.FCONST_0));
        break;
      case Type.DOUBLE:
        insnList.add(new InsnNode(Opcodes.DCONST_0));
        break;
      default:
        insnList.add(new InsnNode(Opcodes.ACONST_NULL));
        break;
    }
    insnList.add(new VarInsnNode(localVarType.getOpcode(Opcodes.ISTORE), localVar.index));
  }

  private static void extendRange(LocalVariableNode varNode, LabelNode first, LabelNode last) {
    varNode.start = first; // Set the start of the variable to the first instruction
    varNode.end = last; // Set the end of the variable to the last instruction
  }

  private static void scanLocalVariableTable(
      List<LocalVariableNode> localVariables,
      Map<Integer, Integer> countBySlot,
      Map<String, Integer> countByName) {
    if (localVariables == null) {
      return; // No local variables to process
    }
    for (LocalVariableNode varNode : localVariables) {
      int slot = varNode.index;
      countBySlot.compute(slot, (k, v) -> (v == null) ? 1 : v + 1);
      countByName.compute(varNode.name, (k, v) -> (v == null) ? 1 : v + 1);
    }
  }

  private static void scanInstructions(
      InsnList instructions, Map<Integer, SlotInfo> slots, Set<Integer> forbiddenSlots) {
    for (int i = 0; i < instructions.size(); i++) {
      AbstractInsnNode insn = instructions.get(i);
      if (insn instanceof VarInsnNode) {
        VarInsnNode varInsn = (VarInsnNode) insn;
        int slot = varInsn.var;
        SlotInfo slotInfo = slots.computeIfAbsent(slot, k -> new SlotInfo(slot));
        if (isStoreOperation(varInsn.getOpcode())) {
          // define var
          int opCode = varInsn.getOpcode();
          slotInfo.addDefinition(i, opCode);
          if (opCode == Opcodes.LSTORE || opCode == Opcodes.DSTORE) {
            // Long and Double occupy two slots, so we need to skip the next slot
            // to avoid double counting
            if (forbiddenSlots != null) {
              forbiddenSlots.add(slot + 1);
            }
          }
        }
        if (isLoadOperation(varInsn.getOpcode())) {
          // use var
          slotInfo.addUse(i, varInsn.getOpcode());
          // assume forbidden slots are already fill with definitions
        }
      }
    }
  }

  private static boolean isLoadOperation(int opcode) {
    return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD;
  }

  private static boolean isStoreOperation(int opcode) {
    return opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
  }

  private static class SlotInfo {
    final int slot;
    final List<Definition> definitions = new ArrayList<>();
    final List<Use> uses = new ArrayList<>();

    SlotInfo(int slot) {
      this.slot = slot;
    }

    void addDefinition(int index, int type) {
      definitions.add(new Definition(index, type));
    }

    void addUse(int index, int type) {
      uses.add(new Use(index, type));
    }

    public int getDefinitionCount() {
      return definitions.size();
    }

    public boolean isOnlyOneType() {
      return definitions.stream().map(d -> d.type).distinct().count() == 1;
    }

    public boolean isSingleSlotType() {
      return definitions.stream()
          .map(d -> d.type)
          .distinct()
          .allMatch(type -> type != Opcodes.DSTORE && type != Opcodes.LSTORE);
    }
  }

  private static class Definition {
    final int index; // ASM instruction index
    final int type; // type of the variable based on OpCode (e.g., ISORE, ASTORe, etc.)

    Definition(int index, int type) {
      this.index = index;
      this.type = type;
    }
  }

  private static class Use {
    final int index; // ASM instruction index
    final int type; // type of the variable based on OpCode (e.g., ILOAD, ALOAD, etc.)

    Use(int index, int type) {
      this.index = index;
      this.type = type;
    }
  }
}
