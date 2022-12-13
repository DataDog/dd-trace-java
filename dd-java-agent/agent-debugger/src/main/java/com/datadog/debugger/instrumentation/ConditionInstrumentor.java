package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeVirtual;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.SNAPSHOTPROVIDER_TYPE;
import static com.datadog.debugger.instrumentation.Types.SNAPSHOT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;

import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.*;
import com.datadog.debugger.el.predicates.AndPredicate;
import com.datadog.debugger.el.predicates.EqualsPredicate;
import com.datadog.debugger.el.predicates.GreaterThanPredicate;
import com.datadog.debugger.el.predicates.OrPredicate;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

public class ConditionInstrumentor implements Visitor<Type> {
  private final String probeId;
  private final ProbeCondition probeCondition;
  private final ClassLoader classLoader;
  private InsnList insnList;
  private final ClassNode classNode;
  private final MethodNode methodNode;
  private int localSnapshotVarIndex;
  private int localResultVarIndex = -1;
  private Type resultType;

  public ConditionInstrumentor(
      String probeId,
      ProbeCondition probeCondition,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode) {
    this.probeId = probeId;
    this.probeCondition = probeCondition;
    this.classLoader = classLoader;
    this.classNode = classNode;
    this.methodNode = methodNode;
  }

  public MethodNode generateEvalMethod() {
    int staticAccess = methodNode.access & Opcodes.ACC_STATIC;
    String paramsDesc = methodNode.desc.substring(1, methodNode.desc.indexOf(')'));
    paramsDesc += Types.SNAPSHOT_TYPE.getDescriptor();
    Type returnType = Type.getReturnType(methodNode.desc);
    if (returnType != Type.VOID_TYPE) {
      paramsDesc += returnType.getDescriptor();
    }
    paramsDesc = "(" + paramsDesc + ")Z";
    Type[] argumentTypes = Type.getArgumentTypes(paramsDesc);
    int argOffset = staticAccess != 0 ? 0 : 1;
    if (argumentTypes[argumentTypes.length - 1] == SNAPSHOT_TYPE) {
      localSnapshotVarIndex = argOffset + argumentTypes.length - 1;
    } else {
      localResultVarIndex = argOffset + argumentTypes.length - 1;
      resultType = argumentTypes[argumentTypes.length - 1];
      localSnapshotVarIndex = argOffset + argumentTypes.length - 2;
    }
    MethodNode evalCondition =
        new MethodNode(
            Opcodes.ACC_PRIVATE | staticAccess, "$evalCondition$", paramsDesc, null, null);
    insnList = evalCondition.instructions;
    LabelNode startNode = new LabelNode();
    insnList.add(startNode);
    probeCondition.accept(this);
    evalCondition.instructions.add(new InsnNode(Opcodes.IRETURN));
    LabelNode endNode = new LabelNode();
    insnList.add(endNode);
    InsnList handler = new InsnList();
    LabelNode handlerLabel = new LabelNode();
    handler.add(handlerLabel);
    // stack [exception]
    handler.add(new VarInsnNode(Opcodes.ALOAD, localSnapshotVarIndex));
    // stack [exception, snapshot]
    LabelNode targetNode = new LabelNode();
    LabelNode gotoNode = new LabelNode();
    handler.add(new JumpInsnNode(Opcodes.IFNONNULL, targetNode));
    // stack [exception]
    ldc(handler, probeId);
    // stack [exception, string]
    ldc(handler, Type.getObjectType(classNode.name));
    // stack [exception, string, string]
    invokeStatic(
        handler, SNAPSHOTPROVIDER_TYPE, "newSnapshot", SNAPSHOT_TYPE, STRING_TYPE, CLASS_TYPE);
    // stack [exception, snapshot]
    handler.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    handler.add(targetNode);
    handler.add(new VarInsnNode(Opcodes.ALOAD, localSnapshotVarIndex));
    // stack [exception, snapshot]
    handler.add(gotoNode);
    handler.add(new InsnNode(Opcodes.SWAP));
    // stack [snapshot, exception]
    ldc(handler, probeCondition.getDslExpression());
    // stack [snapshot, exception string]
    invokeVirtual(
        handler,
        Types.SNAPSHOT_TYPE,
        "handleException",
        Type.VOID_TYPE,
        Type.getType(Exception.class),
        STRING_TYPE);
    // stack []
    handler.add(new InsnNode(Opcodes.ICONST_0));
    // stack [false]
    handler.add(new InsnNode(Opcodes.IRETURN));
    insnList.add(handler);
    evalCondition.tryCatchBlocks.add(
        new TryCatchBlockNode(
            startNode, endNode, handlerLabel, Type.getInternalName(Exception.class)));
    return evalCondition;
  }

  public void callEvalMethod(
      InsnList insnList, MethodNode evalMethodNode, int snapshotVarIndex, int resultVarIndex) {
    boolean isStatic = (evalMethodNode.access & Opcodes.ACC_STATIC) != 0;
    if (!isStatic) {
      insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
    }
    int argOffset = isStatic ? 0 : 1;
    Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
    for (Type argType : argTypes) {
      insnList.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), argOffset));
      argOffset += argType.getSize();
    }
    // add snapshot instance
    insnList.add(new VarInsnNode(Opcodes.ALOAD, snapshotVarIndex));
    if (resultVarIndex > -1) {
      Type returnType = Type.getReturnType(methodNode.desc);
      insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), resultVarIndex));
    }
    insnList.add(
        new MethodInsnNode(
            isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
            classNode.name,
            evalMethodNode.name,
            evalMethodNode.desc));
  }

  @Override
  public Type visit(BinaryExpression binaryExpression) {
    if (binaryExpression.getCombiner() == OrPredicate.OR) {
      binaryExpression.getLeft().accept(this);
      // stack [boolean]
      LabelNode targetLeftNode = new LabelNode();
      LabelNode gotoNode = new LabelNode();
      insnList.add(new JumpInsnNode(Opcodes.IFNE, targetLeftNode));
      // stack []
      binaryExpression.getRight().accept(this);
      // stack [boolean]
      LabelNode targetRightNode = new LabelNode();
      insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetRightNode));
      insnList.add(targetLeftNode);
      // stack []
      insnList.add(new InsnNode(Opcodes.ICONST_1));
      // stack [true]
      insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));

      insnList.add(targetRightNode);
      // stack []
      insnList.add(new InsnNode(Opcodes.ICONST_0));
      // stack [false]
      insnList.add(gotoNode);
    } else if (binaryExpression.getCombiner() == AndPredicate.AND) {
      binaryExpression.getLeft().accept(this);
      // stack [boolean]
      LabelNode targetNode = new LabelNode();
      LabelNode gotoNode = new LabelNode();
      insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
      // stack []
      binaryExpression.getRight().accept(this);
      // stack [boolean]
      insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
      // stack []
      insnList.add(new InsnNode(Opcodes.ICONST_1));
      // stack [true]
      insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));

      insnList.add(targetNode);
      // stack []
      insnList.add(new InsnNode(Opcodes.ICONST_0));
      // stack [false]
      insnList.add(gotoNode);
    }
    return Type.BOOLEAN_TYPE;
  }

  private Type widen(Type type) {
    if (isPrimitive(type)) {
      if (type.getSort() == Type.INT) {
        insnList.add(new InsnNode(Opcodes.I2L));
        return Type.LONG_TYPE;
      }
      if (type.getSort() == Type.FLOAT) {
        insnList.add(new InsnNode(Opcodes.F2D));
        return Type.DOUBLE_TYPE;
      }
    }
    return type;
  }

  @Override
  public Type visit(ComparisonExpression comparisonExpression) {
    Type leftType = comparisonExpression.getLeft().accept(this);
    leftType = widen(leftType);
    Type rightType = comparisonExpression.getRight().accept(this);
    rightType = widen(rightType);
    if (comparisonExpression.getCombiner() == EqualsPredicate.EQ) {
      if (isPrimitive(leftType)) {
        if (leftType == Type.LONG_TYPE && rightType == Type.LONG_TYPE) {
          insnList.add(new InsnNode(Opcodes.LCMP));
          // stack [int]
          addComparisonInsn(insnList, Opcodes.IFNE);
        } else {
          throw new IllegalArgumentException(
              "Unsupported comparison: " + leftType + " <=> " + rightType);
        }
        // stack [boolean]
      } else {
        // if String or object, InvokeVirtual equals
        insnList.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                leftType.getInternalName(),
                "equals",
                Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(Object.class)),
                false));
        // stack: [boolean]
      }
    } else if (comparisonExpression.getCombiner() == GreaterThanPredicate.GT) {
      if (leftType == Type.LONG_TYPE && rightType == Type.LONG_TYPE) {
        insnList.add(new InsnNode(Opcodes.LCMP));
        // stack [int]
        addComparisonInsn(insnList, Opcodes.IFLE);
        // stack [boolean]
      } else {
        throw new IllegalArgumentException(
            "Unsupported comparison: " + leftType + " <=> " + rightType);
      }
    } else {
      throw new IllegalArgumentException(
          "Unsupported combiner: " + comparisonExpression.getCombiner());
    }
    return Type.BOOLEAN_TYPE;
  }

  private static void addComparisonInsn(InsnList insnList, int opcode) {
    LabelNode targetNode = new LabelNode();
    LabelNode gotoNode = new LabelNode();
    insnList.add(new JumpInsnNode(opcode, targetNode));
    insnList.add(new InsnNode(Opcodes.ICONST_1)); // true
    insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    insnList.add(targetNode);
    insnList.add(new InsnNode(Opcodes.ICONST_0)); // false
    insnList.add(gotoNode);
  }

  @Override
  public Type visit(FilterCollectionExpression filterCollectionExpression) {
    filterCollectionExpression.getSource().accept(this);
    filterCollectionExpression.getFilterExpression().accept(this);
    return Type.VOID_TYPE;
  }

  @Override
  public Type visit(HasAllExpression hasAllExpression) {
    hasAllExpression.getValueExpression().accept(this);
    hasAllExpression.getFilterPredicateExpression().accept(this);
    return Type.BOOLEAN_TYPE;
  }

  @Override
  public Type visit(HasAnyExpression hasAnyExpression) {
    hasAnyExpression.getValueExpression().accept(this);
    hasAnyExpression.getFilterPredicateExpression().accept(this);
    return Type.BOOLEAN_TYPE;
  }

  @Override
  public Type visit(IfElseExpression ifElseExpression) {
    ifElseExpression.getTest().accept(this);
    ifElseExpression.getThenExpression().accept(this);
    ifElseExpression.getElseExpression().accept(this);
    return Type.VOID_TYPE;
  }

  @Override
  public Type visit(IfExpression ifExpression) {
    ifExpression.getTest().accept(this);
    ifExpression.getExpression().accept(this);
    return Type.VOID_TYPE;
  }

  @Override
  public Type visit(IsEmptyExpression isEmptyExpression) {
    isEmptyExpression.getValueExpression().accept(this);
    return Type.BOOLEAN_TYPE;
  }

  @Override
  public Type visit(IsUndefinedExpression isUndefinedExpression) {
    isUndefinedExpression.getValueExpression().accept(this);
    return Type.BOOLEAN_TYPE;
  }

  @Override
  public Type visit(LenExpression lenExpression) {
    lenExpression.getSource().accept(this);
    return Type.LONG_TYPE;
  }

  @Override
  public Type visit(NotExpression notExpression) {
    notExpression.getPredicate().accept(this);
    return Type.BOOLEAN_TYPE;
  }

  @Override
  public Type visit(ValueRefExpression valueRefExpression) {
    String symbolName = valueRefExpression.getSymbolName();
    if (symbolName.startsWith("@")) {
      switch (symbolName) {
        case "@return":
          // TODO:
          // declare a top level local var for result with return-type
          // in processInstruction (snapshotInstrumentor) store return value in this var
          // pass var slot index to this class and use it like snapshot var
          if (localResultVarIndex > -1) {
            insnList.add(new VarInsnNode(resultType.getOpcode(Opcodes.ILOAD), localResultVarIndex));
            return resultType;
          } else {
            throw new IllegalArgumentException("@return not available (void?)");
          }
        case "@duration":
          insnList.add(new VarInsnNode(Opcodes.ALOAD, localSnapshotVarIndex));
          // stack [snapshot]
          insnList.add(
              new MethodInsnNode(
                  Opcodes.INVOKEVIRTUAL,
                  Types.SNAPSHOT_TYPE.getInternalName(),
                  "retrieveDuration",
                  "()J"));
          // stack [long]
          return Type.LONG_TYPE;
      }
    }
    // lookup in local vars
    for (int i = 0; i < methodNode.localVariables.size(); i++) {
      if (methodNode.localVariables.get(i).name.equals(symbolName)) {
        Type type = Type.getType(methodNode.localVariables.get(i).desc);
        insnList.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), i));
        // stack [var]
        return type;
      }
    }
    // lookup in fields
    for (FieldNode fieldNode : classNode.fields) {
      if (fieldNode.name.equals(symbolName)) {
        Type type = Type.getType(fieldNode.desc); // lexical type. want the dynamic type?
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // stack [this]
        insnList.add(
            new FieldInsnNode(Opcodes.GETFIELD, classNode.name, fieldNode.name, fieldNode.desc));
        // stack [field]
        return type;
      }
    }
    // not found symbol
    throw new IllegalArgumentException("Cannot find symbol: " + symbolName);
  }

  private Method handleDuplicateMethod(Method m1, Method m2) {
    return m1.getParameterCount() < m2.getParameterCount() ? m1 : m2;
  }

  @Override
  public Type visit(GetMemberExpression getMemberExpression) {
    Type targetType = getMemberExpression.getTarget().accept(this);
    // stack [ref]
    String memberName = getMemberExpression.getMemberName();
    Class<?> clazz = null;
    try {
      clazz = Class.forName(targetType.getClassName(), false, classLoader);
    } catch (ClassNotFoundException ex) {
      ex.printStackTrace(); // FIXME
    }
    Map<String, Field> fields =
        Arrays.stream(clazz.getDeclaredFields())
            .collect(Collectors.toMap(Field::getName, Function.identity()));
    Field field = fields.get(memberName);
    if (field != null) {
      insnList.add(
          new FieldInsnNode(
              Opcodes.GETFIELD,
              targetType.getClassName(),
              memberName,
              Type.getDescriptor(field.getType())));
      // stack [field]
      return Type.getType(field.getType());
    }
    // lookup for getter
    Map<String, Method> methods =
        Arrays.stream(clazz.getMethods())
            .collect(
                Collectors.toMap(
                    Method::getName, Function.identity(), this::handleDuplicateMethod));
    // getValue()
    Method method = methods.get("get" + capitalize(memberName));
    if (method == null) {
      // value()
      method = methods.get(memberName);
    }
    if (method == null) {
      // isValue
      method = methods.get("is" + capitalize(memberName));
    }
    if (method != null) {
      if (method.getParameterCount() > 0) {
        throw new IllegalArgumentException(
            "Cannot call this method, only getters: " + method.getName());
      }
      if (clazz.isInterface()) {
        insnList.add(
            new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                targetType.getInternalName(),
                method.getName(),
                Type.getMethodDescriptor(method),
                true));
      } else {
        insnList.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                targetType.getInternalName(),
                method.getName(),
                Type.getMethodDescriptor(method)));
      }
      return Type.getReturnType(method);
    }
    throw new IllegalArgumentException(
        "Cannot resolve member: " + memberName + " from type: " + targetType);
  }

  private String capitalize(String str) {
    return str.substring(0, 1).toUpperCase() + str.substring(1);
  }

  @Override
  public Type visit(WhenExpression whenExpression) {
    return whenExpression.getExpression().accept(this);
  }

  @Override
  public Type visit(StringValue stringValue) {
    ldc(insnList, stringValue.getValue());
    return Types.STRING_TYPE;
  }

  @Override
  public Type visit(NumericValue numericValue) {
    Number number = numericValue.getValue();
    if (number instanceof Long) {
      ldc(insnList, number.longValue());
      return Type.LONG_TYPE;
    }
    throw new IllegalArgumentException("not supported: " + number.getClass().getTypeName());
  }

  @Override
  public Type visit(PredicateExpression predicate) {
    if (predicate == PredicateExpression.TRUE) {
      insnList.add(new InsnNode(Opcodes.ICONST_1));
    } else {
      insnList.add(new InsnNode(Opcodes.ICONST_0));
    }
    return Type.BOOLEAN_TYPE;
  }

  private boolean isPrimitive(Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN:
      case Type.CHAR:
      case Type.BYTE:
      case Type.SHORT:
      case Type.INT:
      case Type.FLOAT:
      case Type.LONG:
      case Type.DOUBLE:
        return true;
    }
    return false;
  }
}
