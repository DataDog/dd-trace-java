package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.getArgOffset;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeConstructor;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeVirtual;
import static com.datadog.debugger.instrumentation.ASMHelper.isStaticField;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.ASMHelper.newInstance;
import static com.datadog.debugger.instrumentation.ASMHelper.newVar;
import static com.datadog.debugger.instrumentation.ASMHelper.tryBox;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.COLLECTION_TYPE;
import static com.datadog.debugger.instrumentation.Types.CONDITION_HELPER_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.OBJECT_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;
import static com.datadog.debugger.instrumentation.Types.THROWABLE_TYPE;
import static datadog.trace.bootstrap.debugger.util.WellKnownClasses.ThrowableFields.BECAUSE_OVERRIDDEN;

import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.BinaryExpression;
import com.datadog.debugger.el.expressions.BinaryOperator;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ComparisonExpression;
import com.datadog.debugger.el.expressions.ComparisonOperator;
import com.datadog.debugger.el.expressions.ContainsExpression;
import com.datadog.debugger.el.expressions.EndsWithExpression;
import com.datadog.debugger.el.expressions.FilterCollectionExpression;
import com.datadog.debugger.el.expressions.GetMemberExpression;
import com.datadog.debugger.el.expressions.HasAllExpression;
import com.datadog.debugger.el.expressions.HasAnyExpression;
import com.datadog.debugger.el.expressions.IfElseExpression;
import com.datadog.debugger.el.expressions.IfExpression;
import com.datadog.debugger.el.expressions.IndexExpression;
import com.datadog.debugger.el.expressions.IsDefinedExpression;
import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.expressions.LenExpression;
import com.datadog.debugger.el.expressions.MatchesExpression;
import com.datadog.debugger.el.expressions.NotExpression;
import com.datadog.debugger.el.expressions.StartsWithExpression;
import com.datadog.debugger.el.expressions.StringPredicateExpression;
import com.datadog.debugger.el.expressions.SubStringExpression;
import com.datadog.debugger.el.expressions.ValueRefExpression;
import com.datadog.debugger.el.expressions.WhenExpression;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NullValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.SetValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import datadog.trace.util.Strings;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class ConditionInstrumenter {

  public static MethodNode generateConditionMethod(
      String probeId,
      int probeIndex,
      ProbeCondition probeCondition,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      boolean atExit) {
    Type returnType = Type.getReturnType(methodNode.desc);
    String paramsDesc =
        generateParamsDesc(
            methodNode.desc, returnType != Type.VOID_TYPE ? returnType : null, atExit);
    return doGenerateConditionMethod(
        probeId,
        "conditionMethod_",
        probeIndex,
        probeCondition,
        classLoader,
        classNode,
        methodNode,
        atExit,
        paramsDesc,
        returnType);
  }

  public static MethodNode generateConditionExceptionMethod(
      String probeId,
      int probeIndex,
      ProbeCondition probeCondition,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode) {
    String paramsDesc = generateParamsDesc(methodNode.desc, Types.THROWABLE_TYPE, true);
    return doGenerateConditionMethod(
        probeId,
        "conditionExceptionMethod_",
        probeIndex,
        probeCondition,
        classLoader,
        classNode,
        methodNode,
        true,
        paramsDesc,
        Types.THROWABLE_TYPE);
  }

  private static MethodNode doGenerateConditionMethod(
      String probeId,
      String prefix,
      int probeIndex,
      ProbeCondition probeCondition,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      boolean atExit,
      String paramsDesc,
      Type returnType) {
    int staticAccess = methodNode.access & Opcodes.ACC_STATIC;
    int argOffset = (staticAccess != 0 ? 0 : 1);
    int returnVarIndex = returnType != Type.VOID_TYPE ? argOffset : -1;
    int argSizes = Type.getArgumentsAndReturnSizes(methodNode.desc);
    int argTotalSize = (argSizes >> 2) + (argSizes & 3);
    int timestampVarIndex = atExit ? argTotalSize : -1;
    String methodName = "$" + prefix + sanitizedProbeId(probeId) + "$";
    LabelNode startNode = new LabelNode();
    LabelNode endNode = new LabelNode();
    MethodNode conditionMethod = generatePredicateMethod(probeCondition.getWhen(), classLoader, classNode, methodNode.localVariables, paramsDesc, returnType, startNode, endNode, staticAccess, methodName, timestampVarIndex, returnVarIndex);
    LabelNode handlerLabel = generateExceptionHandler(probeIndex, probeCondition.getDslExpression(), conditionMethod.instructions);
    conditionMethod.tryCatchBlocks.add(
        new TryCatchBlockNode(
            startNode, endNode, handlerLabel, Type.getInternalName(Exception.class)));
    return conditionMethod;
  }

  private static MethodNode generatePredicateMethod(BooleanExpression booleanExpression, ClassLoader classLoader, ClassNode classNode, List<LocalVariableNode> localVariables, String paramsDesc, Type returnType, LabelNode startNode, LabelNode endNode, int staticAccess, String methodName, int timestampVarIndex, int returnVarIndex) {
    MethodNode conditionMethod =
        new MethodNode(Opcodes.ACC_PRIVATE | staticAccess, methodName, paramsDesc, null, null);
    conditionMethod.maxLocals = getArgOffset(conditionMethod);
    InsnList insnList = conditionMethod.instructions;
    insnList.add(startNode);
    booleanExpression.accept(
        new ConditionVisitor(
            conditionMethod,
            classLoader,
            classNode,
            localVariables,
            timestampVarIndex,
            returnVarIndex,
            returnType));
    insnList.add(new InsnNode(Opcodes.IRETURN));
    insnList.add(endNode);
    return conditionMethod;
  }

  private static String generateParamsDesc(
      String desc, Type returnOrExceptionType, boolean atExit) {
    String paramsDesc = desc.substring(1, desc.indexOf(')'));
    paramsDesc = paramsDesc + (atExit ? "J" : "");
    if (returnOrExceptionType != null) {
      // add return value or exception as parameter in front of the args
      paramsDesc = returnOrExceptionType.getDescriptor() + paramsDesc;
    }
    paramsDesc = "(" + paramsDesc + ")Z";
    return paramsDesc;
  }

  private static LabelNode generateExceptionHandler(int probeIndex, String dslExpression, InsnList insnList) {
    InsnList handler = new InsnList();
    LabelNode handlerLabel = new LabelNode();
    handler.add(handlerLabel);
    // stack [Exception]
    // handler.add(new InsnNode(Opcodes.ATHROW));
    ldc(handler, probeIndex);
    ldc(handler, dslExpression);
    invokeStatic(
        handler,
        DEBUGGER_CONTEXT_TYPE,
        "handleConditionException",
        Type.VOID_TYPE,
        THROWABLE_TYPE,
        Type.INT_TYPE,
        STRING_TYPE);
    handler.add(new InsnNode(Opcodes.ICONST_0)); // false
    handler.add(new InsnNode(Opcodes.IRETURN));
    insnList.add(handler);
    return handlerLabel;
  }

  private static String sanitizedProbeId(String probeId) {
    return probeId.replace('-', '_');
  }

  private static class ConditionVisitor implements Visitor<Type> {
    private final MethodNode conditionMethod;
    private final InsnList insnList;
    private final ClassLoader classLoader;
    private final ClassNode classNode;
    private final List<LocalVariableNode> localVariables;
    private final int timestampVarIndex;
    private final int returnVarIndex;
    private final Type returnType;
    private int lambdaCounter;

    public ConditionVisitor(
        MethodNode conditionMethod,
        ClassLoader classLoader,
        ClassNode classNode,
        List<LocalVariableNode> localVariables,
        int timestampVarIndex,
        int returnVarIndex,
        Type returnType) {
      this.conditionMethod = conditionMethod;
      this.insnList = conditionMethod.instructions;
      this.classLoader = classLoader;
      this.classNode = classNode;
      this.localVariables = localVariables;
      this.timestampVarIndex = timestampVarIndex;
      this.returnVarIndex = returnVarIndex;
      this.returnType = returnType;
    }

    @Override
    public Type visit(BinaryExpression binaryExpression) {
      BinaryOperator operator = binaryExpression.getOperator();
      binaryExpression.getLeft().accept(this);
      int cmpOpCode = operator == BinaryOperator.OR ? Opcodes.IFNE : Opcodes.IFEQ;
      // stack [boolean]
      LabelNode targetNode = new LabelNode();
      LabelNode gotoNode = new LabelNode();
      insnList.add(new JumpInsnNode(cmpOpCode, targetNode));
      // stack []
      binaryExpression.getRight().accept(this);
      // stack [boolean]
      if (operator == BinaryOperator.OR) {
        LabelNode targetRightNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetRightNode));
        insnList.add(targetNode);
        // stack []
        insnList.add(new InsnNode(Opcodes.ICONST_1));
        // stack [true]
        insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));

        insnList.add(targetRightNode);
        // stack []
        insnList.add(new InsnNode(Opcodes.ICONST_0));
        // stack [false]
        insnList.add(gotoNode);
      } else if (operator == BinaryOperator.AND) {
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

    @Override
    public Type visit(BinaryOperator operator) {
      // not used
      return Type.VOID_TYPE;
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
      if (comparisonExpression.getOperator() != ComparisonOperator.INSTANCEOF) {
        // don't widen for instanceof. Want to preserve original type (ex: 1 instanceof Integer)
        leftType = widen(leftType);
      }
      Type rightType = comparisonExpression.getRight().accept(this);
      rightType = widen(rightType);
      switch (comparisonExpression.getOperator()) {
        case EQ:
          equalsOperator(leftType, rightType);
          break;
        case GT:
          comparisonOperator(leftType, rightType, Opcodes.IFGT);
          break;
        case GE:
          comparisonOperator(leftType, rightType, Opcodes.IFGE);
          break;
        case LT:
          comparisonOperator(leftType, rightType, Opcodes.IFLT);
          break;
        case LE:
          comparisonOperator(leftType, rightType, Opcodes.IFLE);
          break;
        case INSTANCEOF:
          instanceOfOperator(leftType, rightType);
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported operator: " + comparisonExpression.getOperator());
      }
      return Type.BOOLEAN_TYPE;
    }

    private void instanceOfOperator(Type leftType, Type rightType) {
      if (rightType.equals(STRING_TYPE)) {
        // stack [left_value, right_value]
        int varId = newVar(this.conditionMethod, rightType);
        insnList.add(new VarInsnNode(rightType.getOpcode(Opcodes.ISTORE), varId));
        // stack [left_value]
        tryBox(leftType, insnList);
        // stack [left_value_boxed]
        insnList.add(new VarInsnNode(rightType.getOpcode(Opcodes.ILOAD), varId));
        // stack [left_value_boxed, right_value]
        invokeStatic(
            insnList,
            CONDITION_HELPER_TYPE,
            "equalsWithInstanceOf",
            Type.BOOLEAN_TYPE,
            OBJECT_TYPE,
            STRING_TYPE);
      } else {
        throw new IllegalArgumentException("Invalid arguments for instanceof operator");
      }
    }

    private void equalsOperator(Type leftType, Type rightType) {
      if (isPrimitive(leftType) || isPrimitive(rightType)) {
        if (leftType == Type.LONG_TYPE && rightType == Type.LONG_TYPE) {
          insnList.add(new InsnNode(Opcodes.LCMP));
          // stack [int]
          addComparisonInsn(insnList, Opcodes.IFEQ);
        } else if (isIntCompatible(leftType) && isIntCompatible(rightType)) {
          addComparisonInsn(insnList, Opcodes.IF_ICMPEQ);
        } else if (isNumeric(leftType) && isNumeric(rightType)) {
          addHeterogeneousComparison(leftType, rightType, Opcodes.IFEQ);
        } else {
          throw new IllegalArgumentException(
              "Unsupported equals comparison: "
                  + leftType.getClassName()
                  + " <=> "
                  + rightType.getClassName());
        }
        // stack [boolean]
      } else if (isEnum(leftType) && rightType.getClassName().equals(String.class.getTypeName())) {
        invokeStatic(
            insnList,
            CONDITION_HELPER_TYPE,
            "equalsForEnum",
            Type.BOOLEAN_TYPE,
            Types.ENUM_TYPE,
            STRING_TYPE);
      } else if (isEnum(rightType) && leftType.getClassName().equals(String.class.getTypeName())) {
        insnList.add(new InsnNode(Opcodes.SWAP));
        invokeStatic(
            insnList,
            CONDITION_HELPER_TYPE,
            "equalsForEnum",
            Type.BOOLEAN_TYPE,
            Types.ENUM_TYPE,
            STRING_TYPE);
      } else {
        // if String or object, InvokeVirtual equals
        invokeVirtual(insnList, leftType, "equals", Type.BOOLEAN_TYPE, OBJECT_TYPE);
        // stack: [boolean]
      }
    }

    private boolean isIntCompatible(Type type) {
      return type == Type.BOOLEAN_TYPE
          || type == Type.BYTE_TYPE
          || type == Type.SHORT_TYPE
          || type == Type.INT_TYPE;
    }

    private void comparisonOperator(Type leftType, Type rightType, int cmpOpcode) {
      // stack [value_left, value_right]
      if (leftType == Type.LONG_TYPE && rightType == Type.LONG_TYPE) {
        insnList.add(new InsnNode(Opcodes.LCMP));
        // stack [int]
        addComparisonInsn(insnList, cmpOpcode);
        // stack [boolean]
      } else if (leftType.equals(STRING_TYPE) && rightType.equals(STRING_TYPE)) {
        invokeVirtual(insnList, leftType, "compareTo", Type.INT_TYPE, STRING_TYPE);
        // stack [int]
        addComparisonInsn(insnList, cmpOpcode);
        // stack [boolean]
      } else if (leftType.equals(Type.DOUBLE_TYPE) && rightType.equals(Type.DOUBLE_TYPE)) {
        if (cmpOpcode == Opcodes.IFGE || cmpOpcode == Opcodes.IFGT) {
          insnList.add(new InsnNode(Opcodes.DCMPG));
          addComparisonInsn(insnList, cmpOpcode);
        }
        if (cmpOpcode == Opcodes.IFLE || cmpOpcode == Opcodes.IFLT) {
          insnList.add(new InsnNode(Opcodes.DCMPL));
          // stack [int]
          addComparisonInsn(insnList, cmpOpcode);
        }
      } else if (isNumeric(leftType) && isNumeric(rightType)) {
        // Use RuntimeHelper to perform comparison with heterogeneous types
        addHeterogeneousComparison(leftType, rightType, cmpOpcode);
      } else {
        throw new IllegalArgumentException(
            "Unsupported comparison: "
                + leftType.getClassName()
                + " <=> "
                + rightType.getClassName());
      }
    }

    private void addHeterogeneousComparison(Type leftType, Type rightType, int cmpOpcode) {
      int varId = newVar(this.conditionMethod, rightType);
      insnList.add(new VarInsnNode(rightType.getOpcode(Opcodes.ISTORE), varId));
      // stack [left_value]
      tryBox(leftType, insnList);
      // stack [left_value_boxed]
      insnList.add(new VarInsnNode(rightType.getOpcode(Opcodes.ILOAD), varId));
      // stack [left_value_boxed, right_value]
      tryBox(rightType, insnList);
      // stack [left_value_boxed, right_value_boxed]
      ldc(insnList, cmpOpcode);
      // stack [value_left_boxed, value_right_boxed, cmpOpCode]
      invokeStatic(
          insnList,
          CONDITION_HELPER_TYPE,
          "compareTo",
          Type.BOOLEAN_TYPE,
          OBJECT_TYPE,
          OBJECT_TYPE,
          Type.INT_TYPE);
      // stack [boolean]
    }

    private boolean isNumeric(Type type) {
      return type == Type.LONG_TYPE
          || type == Type.DOUBLE_TYPE
          || type == Type.INT_TYPE
          || type == Type.FLOAT_TYPE
          || type.equals(Types.BIG_DECIMAL_TYPE);
    }

    @Override
    public Type visit(ComparisonOperator operator) {
      throw new UnsupportedOperationException("visit ComparisonOperator");
    }

    @Override
    public Type visit(ContainsExpression containsExpression) {
      Type sourceType = containsExpression.getTarget().accept(this);
      // stack [Source]
      Type valueType = containsExpression.getValue().accept(this);
      // stack [Source, Value]
      if (sourceType.equals(STRING_TYPE)) {
        invokeVirtual(
            insnList, sourceType, "contains", Type.BOOLEAN_TYPE, Type.getType(CharSequence.class));
      } else {
        tryBox(valueType, insnList);
        invokeStatic(
            insnList,
            CONDITION_HELPER_TYPE,
            "contains",
            Type.BOOLEAN_TYPE,
            OBJECT_TYPE,
            OBJECT_TYPE);
      }
      // stack [boolean]
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(EndsWithExpression endsWithExpression) {
      return visitStringPredicate(endsWithExpression, "endsWith");
    }

    private static void addComparisonInsn(InsnList insnList, int opcode) {
      LabelNode targetNode = new LabelNode();
      LabelNode gotoNode = new LabelNode();
      insnList.add(new JumpInsnNode(opcode, targetNode));
      insnList.add(new InsnNode(Opcodes.ICONST_0)); // false
      insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
      insnList.add(targetNode);
      insnList.add(new InsnNode(Opcodes.ICONST_1)); // true
      insnList.add(gotoNode);
    }

    static final Class<?>[] ITERATOR_CLASS = new Class<?>[] {
        null, // void
        Boolean.TYPE,
        Character.TYPE,
        Byte.TYPE,
        Short.TYPE,
        Integer.TYPE,
        Float.TYPE,
        Long.TYPE,
        Double.TYPE,
        null, // array
        Object.class
    };

    static final String[] PREDICATE_DESCRIPTORS = new String[] {
        null, // void
        "Ldatadog/trace/bootstrap/debugger/ConditionHelper$BooleanPredicate;",
        "Ljava/util/function/IntPredicate;", // char
        "Ljava/util/function/IntPredicate;", // byte
        "Ljava/util/function/IntPredicate;", // short
        "Ljava/util/function/IntPredicate;", // int
        "Ljava/util/function/DoublePredicate;", // float
        "Ljava/util/function/LongPredicate;", // long
        "Ljava/util/function/DoublePredicate;", // double
        null, // array
        "Ljava/util/function/Predicate;" // Object
    };

    static final String[] COLLECTION_METHOD_SUFFIXES = {
        null, // void
        "BooleanArray",
        "CharArray",
        "ByteArray",
        "ShortArray",
        "IntArray",
        "FloatArray",
        "LongArray",
        "DoubleArray",
        null, // array
        "ObjectArray"
    };

    static class PredicateInfo {
      String iterateMethodName;
      String predicateMethodRefIndyDesc;
      Class<?> iteratorClass;
      Type iteratorType;
      Type predicateInputType;
      Type predicateReturnType;
      List<LocalVariableNode> additionalParam;
      List<InsnList> paramPushInsns;
      String desc;
    }

    private static PredicateInfo getPredicateInfo(String prefixMethodName, Type sourceType, BooleanExpression booleanExpression, List<LocalVariableNode> localVarNodes, ClassNode classNode) {
      PredicateInfo predicateInfo = new PredicateInfo();
      if (Types.isArray(sourceType)) {
        Type elementType = sourceType.getElementType();
        int elementSort = elementType.getSort();
        if (elementSort < 0 || elementSort >= PREDICATE_DESCRIPTORS.length) {
          throw new IllegalArgumentException("elementSort out of range: "  + elementSort);
        }
        predicateInfo.iterateMethodName = prefixMethodName + COLLECTION_METHOD_SUFFIXES[elementSort];
        predicateInfo.predicateMethodRefIndyDesc =  PREDICATE_DESCRIPTORS[elementSort];
        predicateInfo.iteratorClass = ITERATOR_CLASS[elementSort];
        if (isPrimitive(elementType)) {
          predicateInfo.iteratorType = elementType;
          predicateInfo.predicateInputType = sourceType;
          predicateInfo.predicateReturnType = sourceType;
        } else {
          predicateInfo.iteratorType = Types.OBJECT_TYPE;
          // Object[] is covariant so accept any *[]
          predicateInfo.predicateInputType = Types.OBJECT_ARRAY_TYPE;
          predicateInfo.predicateReturnType = Types.OBJECT_ARRAY_TYPE;
        }
      } else {
        predicateInfo.iterateMethodName = prefixMethodName + "Collection";
        predicateInfo.predicateMethodRefIndyDesc = "Ljava/util/function/Predicate;";
        predicateInfo.iteratorClass = Object.class;
        predicateInfo.iteratorType = Types.OBJECT_TYPE;
        predicateInfo.predicateInputType = COLLECTION_TYPE;
        predicateInfo.predicateReturnType = COLLECTION_TYPE;
      }
      PredicateAnalysisVisitor analysisVisitor = new PredicateAnalysisVisitor();
      booleanExpression.accept(analysisVisitor);
      // analyze captured ValueRef used in the predicate
      predicateInfo.additionalParam = new ArrayList<>();
      predicateInfo.paramPushInsns = new ArrayList<>();
      if (!analysisVisitor.refVariableNames.isEmpty()) {
        for (String symbolName : analysisVisitor.refVariableNames) {
          if (symbolName.equals(ValueReferences.ITERATOR_REF)) {
            // skip @it
            continue;
          }
          LocalVariableNode localVariableNode = localVarNodes.stream()
              .filter(node -> node.name.equals(symbolName))
              .findFirst().orElse(null);
          if (localVariableNode != null) {
            predicateInfo.additionalParam.add(new LocalVariableNode(localVariableNode.name, localVariableNode.desc, null, null, null, predicateInfo.additionalParam.size()));
            InsnList insnList = new InsnList();
            Type type = Type.getType(localVariableNode.desc);
            insnList.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), localVariableNode.index));
            predicateInfo.paramPushInsns.add(insnList);
            continue;
          }
          FieldNode fieldNode = classNode.fields.stream()
              .filter(node -> node.name.equals(symbolName))
              .findFirst().orElse(null);
          if (fieldNode != null) {
            predicateInfo.additionalParam.add(new LocalVariableNode(fieldNode.name, fieldNode.desc, null, null, null, predicateInfo.additionalParam.size()));
            InsnList insnList = new InsnList();
            if (isStaticField(fieldNode)) {
              insnList.add(
                  new FieldInsnNode(
                      Opcodes.GETSTATIC, classNode.name, fieldNode.name, fieldNode.desc));
            } else {
              insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
              // stack [this]
              insnList.add(
                  new FieldInsnNode(
                      Opcodes.GETFIELD, classNode.name, fieldNode.name, fieldNode.desc));
              // stack [field]
            }
            // TODO look up in inherited fields?
            predicateInfo.paramPushInsns.add(insnList);
            continue;
          }
          // search for inherited field?
          throw new IllegalArgumentException("invalid reference for predicate method: " + symbolName);
        }
      }
      String desc = predicateInfo.additionalParam.stream()
          .map(p -> p.desc)
          .collect(Collectors.joining(""));
      predicateInfo.desc = "(" + desc + predicateInfo.iteratorType.getDescriptor() + ")Z";
      predicateInfo.predicateMethodRefIndyDesc = "(" + desc + ")" + predicateInfo.predicateMethodRefIndyDesc;
      return predicateInfo;
    }

    @Override
    public Type visit(FilterCollectionExpression filterCollectionExpression) {
      Type sourceType = filterCollectionExpression.getSource().accept(this);
      // stack [sourceCollection]
      PredicateInfo predicateInfo = getPredicateInfo("filter", sourceType, filterCollectionExpression.getFilterExpression(), localVariables, classNode);
      String methodName = generateCollectionPredicateLambda(filterCollectionExpression.getFilterExpression(), predicateInfo);
      // push additional params (captured refs)
      for (InsnList pushInsn : predicateInfo.paramPushInsns) {
        insnList.add(pushInsn);
      }
      // stack [sourceCollection, capturedRef...]
      pushPredicateMethodRef(
          insnList,
          classNode.name,
          methodName,
          predicateInfo.predicateMethodRefIndyDesc,
          true,
          "test",
          Type.getMethodType(Type.BOOLEAN_TYPE, predicateInfo.iteratorType),
          predicateInfo.desc,
          Type.getMethodType(Type.BOOLEAN_TYPE, predicateInfo.iteratorType)
      );
      // stack [sourceCollection, capturedRef..., PredicateFunc]
      invokeStatic(
          insnList,
          CONDITION_HELPER_TYPE,
          predicateInfo.iterateMethodName,
          predicateInfo.predicateReturnType,
          predicateInfo.predicateInputType,
          Type.getReturnType(predicateInfo.predicateMethodRefIndyDesc));
      // stack [filteredCollection]
      return predicateInfo.predicateReturnType;
    }

    private String generateCollectionPredicateLambda(BooleanExpression booleanExpression, PredicateInfo predicateInfo) {
      LabelNode startNode = new LabelNode();
      LabelNode endNode = new LabelNode();
      String methodName = "lambda" + conditionMethod.name + lambdaCounter++;
      MethodNode predicateMethod = generatePredicateMethod(booleanExpression, classLoader, classNode, predicateInfo.additionalParam, predicateInfo.desc, Type.BOOLEAN_TYPE, startNode, endNode, Opcodes.ACC_STATIC, methodName, -1, -1);
      classNode.methods.add(predicateMethod);
      return methodName;
    }

    @Override
    public Type visit(HasAllExpression hasAllExpression) {
      Type sourceType = hasAllExpression.getValueExpression().accept(this);
      PredicateInfo predicateInfo = getPredicateInfo("all", sourceType, hasAllExpression.getFilterPredicateExpression(), localVariables, classNode);
      String methodName = generateCollectionPredicateLambda(hasAllExpression.getFilterPredicateExpression(), predicateInfo);
      // push additional params (captured refs)
      for (InsnList pushInsn : predicateInfo.paramPushInsns) {
        insnList.add(pushInsn);
      }
      pushPredicateMethodRef(
          insnList,
          classNode.name,
          methodName,
          predicateInfo.predicateMethodRefIndyDesc,
          true,
          "test",
          Type.getMethodType(Type.BOOLEAN_TYPE, predicateInfo.iteratorType),
          predicateInfo.desc,
          Type.getMethodType(Type.BOOLEAN_TYPE, predicateInfo.iteratorType)
      );
      // stack [source, PredicateFunc]
      invokeStatic(
          insnList,
          CONDITION_HELPER_TYPE,
          predicateInfo.iterateMethodName,
          Type.BOOLEAN_TYPE,
          predicateInfo.predicateInputType,
          Type.getReturnType(predicateInfo.predicateMethodRefIndyDesc));
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(HasAnyExpression hasAnyExpression) {
      Type sourceType = hasAnyExpression.getValueExpression().accept(this);
      // stack [source]
      PredicateInfo predicateInfo = getPredicateInfo("any", sourceType,  hasAnyExpression.getFilterPredicateExpression(), localVariables, classNode);
      String methodName = generateCollectionPredicateLambda(hasAnyExpression.getFilterPredicateExpression(), predicateInfo);
      // push additional params (captured refs)
      for (InsnList pushInsn : predicateInfo.paramPushInsns) {
        insnList.add(pushInsn);
      }
      pushPredicateMethodRef(
          insnList,
          classNode.name,
          methodName,
          predicateInfo.predicateMethodRefIndyDesc,
          true,
          "test",
          Type.getMethodType(Type.BOOLEAN_TYPE, predicateInfo.iteratorType),
          predicateInfo.desc,
          Type.getMethodType(Type.BOOLEAN_TYPE, predicateInfo.iteratorType)
      );
      // stack [source, PredicateFunc]
      invokeStatic(
          insnList,
          CONDITION_HELPER_TYPE,
          predicateInfo.iterateMethodName,
          Type.BOOLEAN_TYPE,
          predicateInfo.predicateInputType,
          Type.getReturnType(predicateInfo.predicateMethodRefIndyDesc));
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(IfElseExpression ifElseExpression) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Type visit(IfExpression ifExpression) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Type visit(IsEmptyExpression isEmptyExpression) {
      isEmptyExpression.getValueExpression().accept(this);
      // stack [Value]
      invokeStatic(insnList, CONDITION_HELPER_TYPE, "isEmpty", Type.BOOLEAN_TYPE, OBJECT_TYPE);
      // stack [boolean]
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(IsDefinedExpression isDefinedExpression) {
      LabelNode isDefinedStart = new LabelNode();
      insnList.add(isDefinedStart);
      LabelNode isDefinedEnd = new LabelNode();
      Type exprType = isDefinedExpression.getValueExpression().accept(this);
      insnList.add(new InsnNode(exprType.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
      insnList.add(new InsnNode(Opcodes.ICONST_1));
      LabelNode afterCatch = new LabelNode();
      insnList.add(new JumpInsnNode(Opcodes.GOTO, afterCatch));
      insnList.add(isDefinedEnd);
      LabelNode handlerLabel = new LabelNode();
      InsnList handler = new InsnList();
      handler.add(handlerLabel);
      // stack [exception]
      // swallow exception
      handler.add(new InsnNode(Opcodes.POP));
      // stack []
      handler.add(new InsnNode(Opcodes.ICONST_0));
      // stack [false]
      conditionMethod.instructions.add(handler);
      conditionMethod.instructions.add(afterCatch);
      conditionMethod.tryCatchBlocks.add(
          new TryCatchBlockNode(isDefinedStart, isDefinedEnd, handlerLabel, null));
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(LenExpression lenExpression) {
      Type sourceType = lenExpression.getSource().accept(this);
      // stack [object_source]
      if (sourceType.equals(STRING_TYPE)) {
        invokeVirtual(insnList, sourceType, "length", Type.INT_TYPE);
      } else {
        invokeStatic(insnList, CONDITION_HELPER_TYPE, "len", Type.INT_TYPE, OBJECT_TYPE);
      }
      // stack [int]
      return Type.INT_TYPE;
    }

    @Override
    public Type visit(MatchesExpression matchesExpression) {
      return visitStringPredicate(matchesExpression, "matches");
    }

    private Type visitStringPredicate(StringPredicateExpression expression, String methodName) {
      Type sourceType = expression.getSourceString().accept(this);
      // stack [String]
      Type exprType = expression.getStr().accept(this);
      if (!exprType.equals(STRING_TYPE)) {
        throw new UnsupportedOperationException("Second operand must be string");
      }
      // stack [String, String]
      if (sourceType.equals(STRING_TYPE)) {
        invokeVirtual(insnList, sourceType, methodName, Type.BOOLEAN_TYPE, STRING_TYPE);
      } else {
        pushPredicateMethodRef(
            insnList,
            "java/lang/String",
            methodName,
            "Ljava/util/function/BiPredicate;",
            false,
            // method name of the SAM
            "test",
            // ASM Type from method in SAM
            Type.getMethodType(Type.BOOLEAN_TYPE, OBJECT_TYPE, OBJECT_TYPE),
            // MethodType from concrete method
            MethodType.methodType(boolean.class, String.class),
            // ASM type of implementation method
            Type.getMethodType(Type.BOOLEAN_TYPE, STRING_TYPE, STRING_TYPE)
            );
        // stack [Object, String, PredicateFunc]
        invokeStatic(
            insnList,
            CONDITION_HELPER_TYPE,
            "stringPredicate",
            Type.BOOLEAN_TYPE,
            OBJECT_TYPE,
            STRING_TYPE,
            Type.getType(BiPredicate.class));
      }
      // stack [boolean]
      return Type.BOOLEAN_TYPE;
    }

    /**
     * @param owner type where the referenced method belong (ex: String class)
     * @param methodName name of the referenced method (ex: matches)
     * @param methodRefIndyDescriptor JVM descriptor of the method ref indy call + params (ex: (Ljava/lang/Object;)Ljava/util/function/BiPredicate;)
     * @param isStatic
     * @param samMethodName method name of the SAM (ex: test)
     * @param samMethodType ASM type of the SAM (Single Abstract Method) site (ex: Consumer<T>::accept)
     * @param concreteMethodType MethodType of the concrete referenced method (ex String::matches => boolean (String))
     * @param implMethodType ASM type of the concrete referenced method (ex: boolean String::matches(String s)
     */
    private static void pushPredicateMethodRef(InsnList insnList, String owner, String methodName, String methodRefIndyDescriptor, boolean isStatic, String samMethodName, Type samMethodType, MethodType concreteMethodType, Type implMethodType) {
      pushPredicateMethodRef(insnList, owner, methodName, methodRefIndyDescriptor, isStatic, samMethodName, samMethodType, concreteMethodType.toMethodDescriptorString(), implMethodType);
    }

    private static void pushPredicateMethodRef(InsnList insnList, String owner, String methodName, String methodRefIndyDescriptor, boolean isStatic, String samMethodName, Type samMethodType, String concreteMethodDesc, Type implMethodType) {
      MethodType bsmType =
          MethodType.methodType(
              CallSite.class,
              MethodHandles.Lookup.class,
              String.class,
              MethodType.class,
              MethodType.class,
              MethodHandle.class,
              MethodType.class);
      Handle bsmHandle =
          new Handle(
              Opcodes.H_INVOKESTATIC,
              "java/lang/invoke/LambdaMetafactory",
              "metafactory",
              bsmType.toMethodDescriptorString(),
              false);
      Handle implHandle =
          new Handle(
              isStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL,
              owner,
              methodName,
              concreteMethodDesc,
              false);
      insnList.add(
          new InvokeDynamicInsnNode(
              samMethodName, methodRefIndyDescriptor, bsmHandle, samMethodType, implHandle, implMethodType));
    }

    @Override
    public Type visit(NotExpression notExpression) {
      notExpression.getPredicate().accept(this);
      // stack [boolean]
      addComparisonInsn(insnList, Opcodes.IFEQ);
      // stack [boolean]
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(StartsWithExpression startsWithExpression) {
      return visitStringPredicate(startsWithExpression, "startsWith");
    }

    @Override
    public Type visit(SubStringExpression subStringExpression) {
      Type sourceType = subStringExpression.getSource().accept(this);
      // stack [String]
      ldc(insnList, subStringExpression.getStartIndex());
      // stack [String, int]
      ldc(insnList, subStringExpression.getEndIndex());
      // stack [String, int, int]
      if (sourceType.equals(STRING_TYPE)) {
        invokeVirtual(insnList, sourceType, "substring", STRING_TYPE, Type.INT_TYPE, Type.INT_TYPE);
      } else {
        invokeStatic(
            insnList,
            CONDITION_HELPER_TYPE,
            "substring",
            STRING_TYPE,
            OBJECT_TYPE,
            Type.INT_TYPE,
            Type.INT_TYPE);
      }
      // stack [String]
      return STRING_TYPE;
    }

    @Override
    public Type visit(ValueRefExpression valueRefExpression) {
      String symbolName = valueRefExpression.getSymbolName();
      if (symbolName.startsWith("@")) {
        return resolveSyntheticVars(symbolName);
      }
      // lookup in local vars
      LocalVariableNode localVariableNode = localVariables.stream()
          .filter(node -> node.name.equals(symbolName))
          .findFirst().orElse(null);
      if (localVariableNode != null) {
        int argOffset = 0; // local var indices includes this already
        if (returnVarIndex >= 0) {
          argOffset += returnType.getSize();
        }
        Type type = Type.getType(localVariableNode.desc);
        insnList.add(
            new VarInsnNode(type.getOpcode(Opcodes.ILOAD), argOffset + localVariableNode.index));
        // stack [var]
        return type;
      }
      // lookup in fields
      FieldNode fieldNode = classNode.fields.stream()
          .filter(node -> node.name.equals(symbolName))
          .findFirst().orElse(null);
      if (fieldNode != null) {
        Type type = Type.getType(fieldNode.desc); // lexical type. want the dynamic type?
        if (isStaticField(fieldNode)) {
          insnList.add(
              new FieldInsnNode(
                  Opcodes.GETSTATIC, classNode.name, fieldNode.name, fieldNode.desc));
        } else {
          insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
          // stack [this]
          insnList.add(
              new FieldInsnNode(
                  Opcodes.GETFIELD, classNode.name, fieldNode.name, fieldNode.desc));
          // stack [field]
        }
        return type;
      }
      // lookup in inherited classes ? assuming classes are already loaded so safe to to use
      // Class.forName()
      // going through ReflectiveFieldValueResolver.resolve(); ? too dynamic?
      // optimize after first execution, to generate actual bytecode access
      String superName = Strings.getClassName(classNode.superName);
      boolean isInherited = false;
      while (superName != null) {
        Class<?> clazz;
        try {
          clazz = Class.forName(superName, false, classLoader);
        } catch (ClassNotFoundException e) {
          throw new RuntimeException(e);
        }
        Map<String, Field> fields =
            Arrays.stream(clazz.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Function.identity()));
        Field field = fields.get(symbolName);
        if (field != null) {
          Type type = Type.getType(field.getType());
          // TODO check for access (public, protected, private)
          boolean isPrivate = Modifier.isPrivate(field.getModifiers());
          if (isStaticField(field) && !isInherited) {
            // /!\ possible duplicated class definition as accessing static inherited field can lead
            // to class clinit
            insnList.add(
                new FieldInsnNode(
                    Opcodes.GETSTATIC, classNode.name, field.getName(), type.getDescriptor()));
          } else {
            insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
            // stack [this]
            if (isPrivate) {
              // use reflection
              return addReflectionFieldResolution(insnList, field);
            }
            insnList.add(
                new FieldInsnNode(
                    Opcodes.GETFIELD, classNode.name, field.getName(), type.getDescriptor()));
            // stack [field]
          }
          return type;
        }
        if (clazz.getSuperclass() == null) {
          break;
        }
        isInherited = true;
        superName = clazz.getSuperclass().getName();
      }
      // not found symbol
      throw new IllegalArgumentException("Cannot find symbol: " + symbolName);
    }

    private Type resolveSyntheticVars(String symbolName) {
      switch (symbolName) {
        case ValueReferences.RETURN_REF:
          if (returnVarIndex == -1) {
            throw new IllegalArgumentException("@return not available (void?)");
          }
          insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnVarIndex));
          return returnType;
        case ValueReferences.DURATION_REF:
          {
            if (timestampVarIndex == -1) {
              throw new IllegalArgumentException("@duration not available (not at exit)");
            }
            invokeStatic(insnList, Type.getType(System.class), "nanoTime", Type.LONG_TYPE);
            // stack [long]
            insnList.add(new VarInsnNode(Opcodes.LLOAD, timestampVarIndex));
            // stack [long, long]
            insnList.add(new InsnNode(Opcodes.LSUB));
            // stack [long]
            return Type.LONG_TYPE;
          }
        case ValueReferences.EXCEPTION_REF:
          // @exception is using returnVarIndex as no return type for uncaught exception
          if (returnVarIndex == -1) {
            throw new IllegalArgumentException("@exception not available");
          }
          insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnVarIndex));
          return Types.THROWABLE_TYPE;
        case ValueReferences.ITERATOR_REF:
          // assume this is a dedicated predicate static method with last argument the `it`
          Type[] argumentTypes = Type.getArgumentTypes(conditionMethod.desc);
          if (argumentTypes.length == 0) {
            throw new IllegalArgumentException("@it not available for the predicate method: " + conditionMethod.name);
          }
          Type lastArgumentType = argumentTypes[argumentTypes.length-1];
          int lastArgIndex = 0;
          for (int i = 0; i < argumentTypes.length - 1; i++) { // except the last arg
            lastArgIndex += argumentTypes[i].getSize();
          }
          insnList.add(new VarInsnNode(lastArgumentType.getOpcode(Opcodes.ILOAD), lastArgIndex));
          return lastArgumentType;
        default:
          throw new IllegalArgumentException("Unsupported symbol: " + symbolName);
      }
    }

    @Override
    public Type visit(GetMemberExpression getMemberExpression) {
      Type targetType = getMemberExpression.getTarget().accept(this);
      // stack [ref]
      String memberName = getMemberExpression.getMemberName();
      Class<?> clazz;
      try {
        clazz = Class.forName(targetType.getClassName(), false, classLoader);
      } catch (ClassNotFoundException ex) {
        throw new RuntimeException(ex);
      }
      Map<String, WellKnownClasses.SpecialFieldInfo> specialTypeAccess =
          WellKnownClasses.getSpecialTypeAccess(clazz);
      if (specialTypeAccess != null) {
        WellKnownClasses.SpecialFieldInfo specialFieldInfo = specialTypeAccess.get(memberName);
        if (specialFieldInfo != null) {
          return addSpecialFieldAccessCall(specialFieldInfo, clazz);
        }
      }
      Map<String, Field> fields =
          Arrays.stream(clazz.getDeclaredFields())
              .collect(Collectors.toMap(Field::getName, Function.identity()));
      Field field = fields.get(memberName);
      if (field != null) {
        // TODO use Field::canAccess method (JDK9+) to make sure we can access it with a GETFIELD
        if (field.isAccessible()) {
          insnList.add(
              new FieldInsnNode(
                  Opcodes.GETFIELD,
                  targetType.getClassName(),
                  memberName,
                  Type.getDescriptor(field.getType())));
          // stack [field]
          return Type.getType(field.getType());
        }
        // emit call to ReflectiveFieldValueResolver.getFieldValue
        return addReflectionFieldResolution(insnList, field);
      }
      // try to resolve base on dynamic types with ConditionHelper
      ldc(insnList, memberName);
      invokeStatic(
          insnList,
          CONDITION_HELPER_TYPE,
          "resolveByFieldName",
          OBJECT_TYPE,
          OBJECT_TYPE,
          STRING_TYPE);
      return OBJECT_TYPE;
    }

    private Type addSpecialFieldAccessCall(
        WellKnownClasses.SpecialFieldInfo specialFieldInfo, Class<?> clazz) {
      // stack [ref]
      if (specialFieldInfo.checksOverride) {
        insnList.add(new InsnNode(Opcodes.DUP));
        // stack [ref, ref]
        ldc(insnList, specialFieldInfo.method.getName());
        // stack [ref, ref, String]
        ldc(insnList, Type.getType(specialFieldInfo.method.getDeclaringClass()));
        // stack [ref, ref, String, Class]
        invokeStatic(
            insnList,
            Type.getType(WellKnownClasses.class),
            "isOverridden",
            Type.BOOLEAN_TYPE,
            OBJECT_TYPE,
            STRING_TYPE,
            CLASS_TYPE);
        // stack [ref, boolean]
        LabelNode targetNode = new LabelNode();
        LabelNode gotoNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
        // stack [ref]
        newInstance(insnList, Type.getType(UnsupportedOperationException.class));
        // stack [ref, Exception]
        insnList.add(new InsnNode(Opcodes.DUP));
        // stack [ref, Exception, Exception]
        ldc(insnList, BECAUSE_OVERRIDDEN);
        // stack [ref, Exception, Exception, String]
        invokeConstructor(insnList, Type.getType(UnsupportedOperationException.class), STRING_TYPE);
        // stack [ref, Exception]
        insnList.add(new InsnNode(Opcodes.ATHROW));
        insnList.add(targetNode);
      }
      if (specialFieldInfo.method.getParameterCount() == 1) {
        // special case for Optional*::orElse calls
        switch (specialFieldInfo.method.getParameterTypes()[0].getName()) {
          case "boolean":
          case "byte":
          case "short":
          case "char":
          case "int":
            ldc(insnList, 0);
            break;
          case "long":
            ldc(insnList, 0L);
            break;
          case "double":
            ldc(insnList, 0.0);
            break;
          default:
            ldc(insnList, null);
        }
      }
      insnList.add(
          new MethodInsnNode(
              Opcodes.INVOKEVIRTUAL,
              Type.getType(clazz).getInternalName(),
              specialFieldInfo.method.getName(),
              Type.getMethodDescriptor(specialFieldInfo.method)));
      return Type.getReturnType(specialFieldInfo.method);
    }

    private Type addReflectionFieldResolution(InsnList insnList, Field field) {
      ldc(insnList, field.getName());
      if (field.getType().getTypeName().equals("int")) {
        invokeStatic(
            insnList,
            Type.getType(ReflectiveFieldValueResolver.class),
            "getFieldValueAsInt",
            Type.INT_TYPE,
            OBJECT_TYPE,
            STRING_TYPE);
        return Type.INT_TYPE;
      }
      // TODO other prim types
      invokeStatic(
          insnList,
          Type.getType(ReflectiveFieldValueResolver.class),
          "getFieldValue",
          OBJECT_TYPE,
          OBJECT_TYPE,
          STRING_TYPE);
      insnList.add(
          new TypeInsnNode(Opcodes.CHECKCAST, Type.getType(field.getType()).getInternalName()));
      return Type.getType(field.getType());
    }

    private String capitalize(String str) {
      return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public Type visit(IndexExpression indexExpression) {
      Type targetType = indexExpression.getTarget().accept(this);
      // stack [target_object]
      Type keyType = indexExpression.getKey().accept(this);
      // stack [target_object, key]
      if (targetType.getSort() == Type.ARRAY
          && (isIntCompatible(keyType) || keyType.equals(Type.LONG_TYPE))) {
        if (keyType.equals(Type.LONG_TYPE)) {
          insnList.add(new InsnNode(Opcodes.L2I)); // convert key long to int
        }
        Type elementType = targetType.getElementType();
        insnList.add(new InsnNode(elementType.getOpcode(Opcodes.IALOAD)));
        return elementType;
      }
      if (isIntCompatible(keyType)) {
        invokeStatic(
            insnList, CONDITION_HELPER_TYPE, "index", OBJECT_TYPE, OBJECT_TYPE, Type.INT_TYPE);
      } else {
        tryBox(keyType, insnList);
        // stack [target_object, key_boxed]
        invokeStatic(
            insnList, CONDITION_HELPER_TYPE, "index", OBJECT_TYPE, OBJECT_TYPE, OBJECT_TYPE);
      }
      return OBJECT_TYPE;
    }

    @Override
    public Type visit(WhenExpression whenExpression) {
      return whenExpression.getExpression().accept(this);
    }

    @Override
    public Type visit(StringValue stringValue) {
      ldc(insnList, stringValue.getValue());
      return STRING_TYPE;
    }

    @Override
    public Type visit(NumericValue numericValue) {
      Number number = numericValue.getWidenValue();
      if (number instanceof Long) {
        ldc(insnList, number.longValue());
        return Type.LONG_TYPE;
      } else if (number instanceof Double) {
        ldc(insnList, number);
        return Type.DOUBLE_TYPE;
      } else if (number instanceof BigDecimal) {
        // use the toString representation to be able to create a BigDecimal instance
        // toString <=> new BigDecimal(String)
        newInstance(insnList, Types.BIG_DECIMAL_TYPE);
        insnList.add(new InsnNode(Opcodes.DUP));
        ldc(insnList, number.toString());
        invokeConstructor(insnList, Types.BIG_DECIMAL_TYPE, STRING_TYPE);
        return Types.BIG_DECIMAL_TYPE;
      }
      throw new IllegalArgumentException("not supported: " + number.getClass().getTypeName());
    }

    @Override
    public Type visit(BooleanValue booleanValue) {
      insnList.add(new InsnNode(booleanValue.getValue() ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(NullValue nullValue) {
      ldc(insnList, null);
      return OBJECT_TYPE;
    }

    @Override
    public Type visit(ListValue listValue) {
      return null;
    }

    @Override
    public Type visit(MapValue mapValue) {
      return null;
    }

    @Override
    public Type visit(SetValue setValue) {
      return null;
    }

    @Override
    public Type visit(BooleanExpression predicate) {
      insnList.add(
          new InsnNode(predicate == BooleanExpression.TRUE ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
      return Type.BOOLEAN_TYPE;
    }

    @Override
    public Type visit(ObjectValue objectValue) {
      return OBJECT_TYPE;
    }

    private static boolean isPrimitive(Type type) {
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

    private boolean isEnum(Type type) {
      Class<?> clazz;
      try {
        clazz = Class.forName(type.getClassName(), false, classLoader);
        return clazz.isEnum();
      } catch (ClassNotFoundException ex) {
        return false;
      }
    }
  }

  private static class PredicateAnalysisVisitor extends RefAnalysisVisitor {
    final List<String> refVariableNames = new ArrayList<>();

    @Override
    public Void visit(ValueRefExpression valueRefExpression) {
      refVariableNames.add(valueRefExpression.getSymbolName());
      return null;
    }
  }
}
