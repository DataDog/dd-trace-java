package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.decodeSignature;
import static com.datadog.debugger.instrumentation.ASMHelper.emitReflectiveCall;
import static com.datadog.debugger.instrumentation.ASMHelper.ensureSafeClassLoad;
import static com.datadog.debugger.instrumentation.ASMHelper.getStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeInterface;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeVirtual;
import static com.datadog.debugger.instrumentation.ASMHelper.isInScope;
import static com.datadog.debugger.instrumentation.ASMHelper.isStaticField;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.Types.*;
import static datadog.trace.util.Strings.getClassName;
import static org.objectweb.asm.Type.DOUBLE_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;

import com.datadog.debugger.el.InvalidValueException;
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
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassFileLines;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles generating instrumentation for metric probes */
public class MetricInstrumenter extends Instrumenter {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetricInstrumenter.class);
  private static final InsnList EMPTY_INSN_LIST = new InsnList();

  private final MetricProbe metricProbe;
  private int durationStartVar = -1;
  private LabelNode durationStartLabel;

  public MetricInstrumenter(
      MetricProbe metricProbe,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices) {
    super(metricProbe, methodInfo, diagnostics, probeIndices);
    this.metricProbe = metricProbe;
  }

  @Override
  public InstrumentationResult.Status instrument() {
    if (definition.isLineProbe()) {
      return addLineMetric(classFileLines);
    }
    switch (definition.getEvaluateAt()) {
      case ENTRY:
      case DEFAULT:
        {
          InsnList insnList = wrapTryCatch(callMetric(metricProbe, null));
          methodNode.instructions.insert(methodEnterLabel, insnList);
          break;
        }
      case EXIT:
        {
          processInstructions();
          addFinallyHandler(returnHandlerLabel);
          installFinallyBlocks();
          break;
        }
      default:
        throw new IllegalArgumentException(
            "Invalid evaluateAt attribute: " + definition.getEvaluateAt());
    }
    return InstrumentationResult.Status.INSTALLED;
  }

  private void addFinallyHandler(LabelNode endLabel) {
    // only for @duration case
    if (durationStartVar == -1) {
      return;
    }
    InsnList insnList = callMetric(metricProbe, null);
    createFinallyHandler(durationStartLabel, endLabel, wrapTryCatch(insnList));
  }

  private InsnList wrapTryCatch(InsnList insnList) {
    if (insnList == null || insnList == EMPTY_INSN_LIST) {
      return EMPTY_INSN_LIST;
    }
    LabelNode startLabel = new LabelNode();
    insnList.insert(startLabel);
    LabelNode endLabel = new LabelNode();
    insnList.add(endLabel);
    InsnList handler = new InsnList();
    LabelNode handlerLabel = new LabelNode();
    handler.add(handlerLabel);
    // stack [exception]
    handler.add(new InsnNode(Opcodes.POP));
    // stack []
    handler.add(new JumpInsnNode(Opcodes.GOTO, endLabel));
    methodNode.instructions.add(handler);
    methodNode.tryCatchBlocks.add(
        new TryCatchBlockNode(
            startLabel, endLabel, handlerLabel, Type.getInternalName(Exception.class)));
    return insnList;
  }

  @Override
  protected InsnList getBeforeReturnInsnList(AbstractInsnNode node) {
    int size = 1;
    int storeOpCode = 0;
    int loadOpCode = 0;
    Type returnType = null;
    switch (node.getOpcode()) {
      case Opcodes.RET:
      case Opcodes.RETURN:
        return wrapTryCatch(callMetric(metricProbe, node));
      case Opcodes.LRETURN:
        storeOpCode = Opcodes.LSTORE;
        loadOpCode = Opcodes.LLOAD;
        size = 2;
        returnType = Type.LONG_TYPE;
        break;
      case Opcodes.DRETURN:
        storeOpCode = Opcodes.DSTORE;
        loadOpCode = Opcodes.DLOAD;
        size = 2;
        returnType = Type.DOUBLE_TYPE;
        break;
      case Opcodes.IRETURN:
        storeOpCode = Opcodes.ISTORE;
        loadOpCode = Opcodes.ILOAD;
        returnType = Type.INT_TYPE;
        break;
      case Opcodes.FRETURN:
        storeOpCode = Opcodes.FSTORE;
        loadOpCode = Opcodes.FLOAD;
        returnType = Type.FLOAT_TYPE;
        break;
      case Opcodes.ARETURN:
        storeOpCode = Opcodes.ASTORE;
        loadOpCode = Opcodes.ALOAD;
        returnType = OBJECT_TYPE;
        break;
      default:
        throw new UnsupportedOperationException("Unsupported opcode: " + node.getOpcode());
    }
    int tmpIdx = newVar(size);
    InsnList insnList =
        wrapTryCatch(
            callMetric(metricProbe, node, new ReturnContext(tmpIdx, loadOpCode, returnType)));
    // store return value from the stack to local before wrapped call
    insnList.insert(new VarInsnNode(storeOpCode, tmpIdx));
    // restore return value to the stack after wrapped call
    insnList.add(new VarInsnNode(loadOpCode, tmpIdx));
    return insnList;
  }

  private void createFinallyHandler(LabelNode startLabel, LabelNode endLabel, InsnList insnList) {
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // declare a local var to store the current Throwable of the 'finally' block
    int throwableTmpVar = newVar(Type.getType(Throwable.class));
    // stack [exception]
    handler.add(new VarInsnNode(Opcodes.ASTORE, throwableTmpVar));
    // stack []
    handler.add(insnList);
    // stack []
    // restore the current Throwable to the stack
    handler.add(new VarInsnNode(Opcodes.ALOAD, throwableTmpVar));
    // stack [exception]
    handler.add(new InsnNode(Opcodes.ATHROW));
    methodNode.instructions.add(handler);
    finallyBlocks.add(new FinallyBlock(startLabel, endLabel, handlerLabel));
  }

  private InsnList callCount(
      MetricProbe metricProbe, AbstractInsnNode targetLocation, ReturnContext returnContext) {
    if (metricProbe.getValue() == null) {
      InsnList insnList = new InsnList();
      ldc(insnList, metricProbe.getProbeId().getEncodedId());
      // stack [ProbeId]
      getStatic(insnList, METRICKIND_TYPE, metricProbe.getKind().name());
      // stack [string, MetricKind]
      insnList.add(new LdcInsnNode(metricProbe.getMetricName()));
      // stack [string, MetricKind, string]
      // consider the metric as an increment counter one
      ldc(insnList, 1L);
      // stack [string, MetricKind, string, long]
      pushTags(insnList, addProbeIdWithTags(metricProbe.getId(), metricProbe.getTags()));
      // stack [string, MetricKind, string, long, array]
      invokeStatic(
          insnList,
          DEBUGGER_CONTEXT_TYPE,
          "metric",
          Type.VOID_TYPE,
          STRING_TYPE,
          METRICKIND_TYPE,
          STRING_TYPE,
          Type.LONG_TYPE,
          Types.asArray(STRING_TYPE, 1));
      // stack []
      return insnList;
    }
    return internalCallMetric(metricProbe, targetLocation, returnContext);
  }

  private InsnList internalCallMetric(
      MetricProbe metricProbe, AbstractInsnNode targetLocation, ReturnContext returnContext) {
    InsnList insnList = new InsnList();
    InsnList nullBranch = new InsnList();
    VisitorResult result;
    Type resultType;
    try {
      result =
          metricProbe
              .getValue()
              .getExpr()
              .accept(new MetricValueVisitor(this, nullBranch, targetLocation, returnContext));
    } catch (InvalidValueException | UnsupportedOperationException ex) {
      reportError(ex.getMessage());
      return EMPTY_INSN_LIST;
    }
    resultType = result.type.getMainType();
    MetricProbe.MetricKind kind = metricProbe.getKind();
    if (!kind.isCompatible(resultType)) {
      String expectedTypes =
          kind.getSupportedTypes().stream()
              .map(Type::getClassName)
              .collect(Collectors.joining(","));
      reportError(
          String.format(
              "Incompatible type for expression: %s with expected types: [%s]",
              resultType.getClassName(), expectedTypes));
      return EMPTY_INSN_LIST;
    }
    resultType = convertIfRequired(resultType, result.insnList);
    ldc(insnList, metricProbe.getProbeId().getEncodedId());
    // stack [string]
    getStatic(insnList, METRICKIND_TYPE, metricProbe.getKind().name());
    // stack [string, MetricKind]
    insnList.add(new LdcInsnNode(metricProbe.getMetricName()));
    // stack [string, MetricKind, string]
    insnList.add(result.insnList);
    // stack [string, MetricKind, string, long|double]
    pushTags(insnList, addProbeIdWithTags(metricProbe.getId(), metricProbe.getTags()));
    // stack [string, MetricKind, string, long|double, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "metric",
        Type.VOID_TYPE,
        STRING_TYPE,
        METRICKIND_TYPE,
        STRING_TYPE,
        resultType,
        Types.asArray(STRING_TYPE, 1));
    // stack []
    insnList.add(nullBranch);
    return insnList;
  }

  private Type convertIfRequired(Type currentType, InsnList insnList) {
    switch (currentType.getSort()) {
      case Type.BYTE:
      case Type.SHORT:
      case Type.CHAR:
      case Type.INT:
      case Type.BOOLEAN:
        insnList.add(new InsnNode(Opcodes.I2L));
        return Type.LONG_TYPE;
      case Type.FLOAT:
        insnList.add(new InsnNode(Opcodes.F2D));
        return Type.DOUBLE_TYPE;
      default:
        return currentType;
    }
  }

  private InsnList callMetric(MetricProbe metricProbe, AbstractInsnNode targetLocation) {
    return callMetric(metricProbe, targetLocation, null);
  }

  private InsnList callMetric(
      MetricProbe metricProbe, AbstractInsnNode targetLocation, ReturnContext returnContext) {
    switch (metricProbe.getKind()) {
      case COUNT:
        return callCount(metricProbe, targetLocation, returnContext);
      case GAUGE:
      case HISTOGRAM:
      case DISTRIBUTION:
        if (metricProbe.getValue() == null) {
          return EMPTY_INSN_LIST;
        }
        return internalCallMetric(metricProbe, targetLocation, returnContext);
      default:
        reportError(String.format("Unknown metric kind: %s", metricProbe.getKind()));
    }
    return null;
  }

  private InstrumentationResult.Status addLineMetric(ClassFileLines classFileLines) {
    Where.SourceLine[] targetLines = metricProbe.getWhere().getSourceLines();
    if (targetLines == null) {
      reportError("Missing line(s) in probe definition.");
      return InstrumentationResult.Status.ERROR;
    }
    if (classFileLines.isEmpty()) {
      reportError("Missing line debug information.");
      return InstrumentationResult.Status.ERROR;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();
      LabelNode beforeLabel = classFileLines.getLineLabel(from);
      // single line N capture translates to line range (N, N+1)
      LabelNode afterLabel =
          classFileLines.getLineLabel(till + (sourceLine.isSingleLine() ? 1 : 0));
      if (beforeLabel == null && afterLabel == null) {
        reportError(
            "No line info for " + (sourceLine.isSingleLine() ? "line " : "range ") + sourceLine);
      }
      if (beforeLabel != null) {
        InsnList insnList = wrapTryCatch(callMetric(metricProbe, beforeLabel));
        methodNode.instructions.insertBefore(beforeLabel.getNext(), insnList);
      }
      if (afterLabel != null && !sourceLine.isSingleLine()) {
        InsnList insnList = wrapTryCatch(callMetric(metricProbe, afterLabel));
        methodNode.instructions.insert(afterLabel, insnList);
      }
    }
    return InstrumentationResult.Status.INSTALLED;
  }

  private static class VisitorResult {
    final ASMHelper.Type type;
    final InsnList insnList;

    public VisitorResult(ASMHelper.Type type, InsnList insnList) {
      this.type = type;
      this.insnList = insnList;
    }
  }

  private class MetricValueVisitor implements Visitor<VisitorResult> {
    private final MetricInstrumenter instrumentor;
    private final InsnList nullBranch;
    private final AbstractInsnNode targetLocation;
    private final ReturnContext returnContext;

    public MetricValueVisitor(
        MetricInstrumenter instrumentor,
        InsnList nullBranch,
        AbstractInsnNode targetLocation,
        ReturnContext returnContext) {
      this.instrumentor = instrumentor;
      this.nullBranch = nullBranch;
      this.targetLocation = targetLocation;
      this.returnContext = returnContext;
    }

    @Override
    public VisitorResult visit(BinaryExpression binaryExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(BinaryOperator operator) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(ComparisonExpression comparisonExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(ComparisonOperator operator) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(ContainsExpression containsExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(EndsWithExpression endsWithExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(FilterCollectionExpression filterCollectionExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(HasAllExpression hasAllExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(HasAnyExpression hasAnyExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(IfElseExpression ifElseExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(IfExpression ifExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(IsEmptyExpression isEmptyExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(IsDefinedExpression isDefinedExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(LenExpression lenExpression) {
      VisitorResult visitorResult = lenExpression.getSource().accept(this);
      Type type = visitorResult.type.getMainType();
      if (type.equals(STRING_TYPE)) {
        invokeVirtual(visitorResult.insnList, type, "length", Type.INT_TYPE);
        return new VisitorResult(ASMHelper.INT_TYPE, visitorResult.insnList);
      }
      if (type.getSort() == Type.ARRAY) {
        visitorResult.insnList.add(new InsnNode(Opcodes.ARRAYLENGTH));
        return new VisitorResult(ASMHelper.INT_TYPE, visitorResult.insnList);
      }
      if (type.equals(COLLECTION_TYPE)
          || type.equals(LIST_TYPE)
          || type.equals(MAP_TYPE)
          || type.equals(SET_TYPE)) {
        invokeInterface(visitorResult.insnList, type, "size", Type.INT_TYPE);
        return new VisitorResult(ASMHelper.INT_TYPE, visitorResult.insnList);
      }
      if (type.equals(ARRAYLIST_TYPE)
          || type.equals(LINKEDLIST_TYPE)
          || type.equals(HASHMAP_TYPE)
          || type.equals(LINKEDHASHMAP_TYPE)
          || type.equals(HASHSET_TYPE)) {
        invokeVirtual(visitorResult.insnList, type, "size", Type.INT_TYPE);
        return new VisitorResult(ASMHelper.INT_TYPE, visitorResult.insnList);
      }
      throw new InvalidValueException("Unsupported type for len operation: " + type.getClassName());
    }

    @Override
    public VisitorResult visit(MatchesExpression matchesExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(NotExpression notExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(StartsWithExpression startsWithExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(SubStringExpression subStringExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(ValueRefExpression valueRefExpression) {
      String name = valueRefExpression.getSymbolName();
      InsnList insnList = new InsnList();
      ASMHelper.Type currentType;
      if (name.equals(ValueReferences.THIS)) {
        currentType = new ASMHelper.Type(Type.getObjectType(instrumentor.classNode.name));
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // stack [this]
      } else {
        if (name.startsWith(ValueReferences.SYNTHETIC_PREFIX)) {
          currentType = tryRetrieveSynthetic(name, insnList);
        } else {
          currentType = tryRetrieve(name, insnList);
        }
        if (currentType == null) {
          throw new InvalidValueException("Cannot resolve symbol " + name);
        }
        // stack [arg|local|field]
      }
      return new VisitorResult(currentType, insnList);
    }

    @Override
    public VisitorResult visit(GetMemberExpression getMemberExpression) {
      VisitorResult result = getMemberExpression.getTarget().accept(this);
      String name = getMemberExpression.getMemberName();
      ASMHelper.Type type =
          tryRetrieveField(result.type.getMainType(), name, result.insnList, false);
      if (type == null) {
        throw new InvalidValueException("Cannot resolve symbol " + name);
      }
      // stack [field]
      return new VisitorResult(type, result.insnList);
    }

    @Override
    public VisitorResult visit(IndexExpression indexExpression) {
      InsnList insnList = new InsnList();
      VisitorResult targetResult = indexExpression.getTarget().accept(this);
      // stack [target_object]
      insnList.add(targetResult.insnList);
      VisitorResult keyResult = indexExpression.getKey().accept(this);
      // stack [target_object, key_object]
      insnList.add(keyResult.insnList);
      Type targetType = targetResult.type.getMainType();
      if (targetType.equals(MAP_TYPE)) {
        invokeInterface(insnList, targetType, "get", OBJECT_TYPE, OBJECT_TYPE);
        // stack [result_object]
        return buildResultWithElementType(targetResult.type, insnList);
      }
      if (targetType.equals(HASHMAP_TYPE)) {
        invokeVirtual(insnList, targetType, "get", OBJECT_TYPE, OBJECT_TYPE);
        // stack [result_object]
        return buildResultWithElementType(targetResult.type, insnList);
      }
      // for now on, we expect to be either an int or a long for accessing lists or arrays
      if (keyResult.type.getMainType().equals(Type.LONG_TYPE)
          || keyResult.type.getMainType().equals(Type.INT_TYPE)) {
        if (keyResult.type.getMainType().equals(Type.LONG_TYPE)) {
          insnList.add(new InsnNode(Opcodes.L2I));
        }
        if (targetType.getSort() == Type.ARRAY) {
          insnList.add(new InsnNode(targetType.getElementType().getOpcode(Opcodes.IALOAD)));
          return new VisitorResult(new ASMHelper.Type(targetType.getElementType()), insnList);
        }
        if (targetType.equals(LIST_TYPE)) {
          invokeInterface(insnList, targetType, "get", OBJECT_TYPE, Type.INT_TYPE);
          return buildResultWithElementType(targetResult.type, insnList);
        }
        if (targetType.equals(ARRAYLIST_TYPE) || targetType.equals(LINKEDLIST_TYPE)) {
          invokeVirtual(insnList, targetType, "get", OBJECT_TYPE, Type.INT_TYPE);
          return buildResultWithElementType(targetResult.type, insnList);
        }
      } else {
        throw new UnsupportedOperationException(
            "Incompatible type for key: " + keyResult.type + ", expected int or long");
      }
      throw new UnsupportedOperationException(targetResult.type.toString());
    }

    private VisitorResult buildResultWithElementType(ASMHelper.Type targetType, InsnList insnList) {
      // assume the first generic type of targetResult is the type of elements
      ASMHelper.Type elementType =
          targetType.getGenericTypes().isEmpty()
              ? ASMHelper.OBJECT_TYPE
              : targetType.getGenericTypes().get(0);
      insnList.add(
          new TypeInsnNode(Opcodes.CHECKCAST, elementType.getMainType().getInternalName()));
      return new VisitorResult(elementType, insnList);
    }

    @Override
    public VisitorResult visit(WhenExpression whenExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(BooleanExpression booleanExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(ObjectValue objectValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(StringValue stringValue) {
      InsnList insnList = new InsnList();
      ldc(insnList, stringValue.getValue());
      return new VisitorResult(ASMHelper.STRING_TYPE, insnList);
    }

    @Override
    public VisitorResult visit(NumericValue numericValue) {
      Number number = numericValue.getWidenValue();
      InsnList insnList = new InsnList();
      if (number instanceof Long) {
        ldc(insnList, number.longValue());
        return new VisitorResult(ASMHelper.LONG_TYPE, insnList);
      }
      throw new InvalidValueException(
          "Unsupported constant value: " + number + " type: " + number.getClass().getTypeName());
    }

    @Override
    public VisitorResult visit(BooleanValue booleanValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(NullValue nullValue) {
      InsnList instList = new InsnList();
      instList.add(new InsnNode(Opcodes.ACONST_NULL));
      return new VisitorResult(ASMHelper.OBJECT_TYPE, instList);
    }

    @Override
    public VisitorResult visit(ListValue listValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(MapValue mapValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(SetValue setValue) {
      throw new UnsupportedOperationException();
    }

    private ASMHelper.Type tryRetrieve(String name, InsnList insnList) {
      ASMHelper.Type result = tryRetrieveArgument(name, insnList);
      if (result != null) {
        return result;
      }
      result = tryRetrieveLocalVar(name, insnList);
      if (result != null) {
        return result;
      }
      return tryRetrieveField(
          Type.getObjectType(instrumentor.classNode.name), name, insnList, true);
    }

    private ASMHelper.Type tryRetrieveArgument(String head, InsnList insnList) {
      Type[] argTypes = Type.getArgumentTypes(instrumentor.methodNode.desc);
      if (argTypes.length == 0) {
        // bail out if no args
        return null;
      }
      int counter = 0;
      int slot = instrumentor.isStatic ? 0 : 1;
      for (Type argType : argTypes) {
        String currentArgName = null;
        if (instrumentor.localVarsBySlotArray.length > 0) {
          LocalVariableNode localVarNode = instrumentor.localVarsBySlotArray[slot];
          currentArgName = localVarNode != null ? localVarNode.name : null;
        }
        if (currentArgName == null) {
          // if argument names are not resolved correctly let's assign p+arg_index
          currentArgName = "p" + counter;
        }
        if (currentArgName.equals(head)) {
          VarInsnNode varInsnNode = new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot);
          insnList.add(varInsnNode);
          // stack [arg]
          return new ASMHelper.Type(argType);
        }
        slot += argType.getSize();
      }
      return null;
    }

    private ASMHelper.Type tryRetrieveLocalVar(String head, InsnList insnList) {
      for (LocalVariableNode varNode : instrumentor.methodNode.localVariables) {
        if (varNode.name.equals(head)) {
          if (isInScope(instrumentor.methodNode, varNode, targetLocation)) {
            Type varType = Type.getType(varNode.desc);
            VarInsnNode varInsnNode =
                new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), varNode.index);
            insnList.add(varInsnNode);
            // stack [local]
            return new ASMHelper.Type(varType);
          }
        }
      }
      return null;
    }

    private ASMHelper.Type tryRetrieveField(
        Type currentType, String fieldName, InsnList insnList, boolean useThisField) {
      Class<?> clazz;
      ASMHelper.Type returnType = null;
      try {
        String className;
        String fieldDesc = null;
        boolean isAccessible = true;
        if (currentType.getInternalName().equals(instrumentor.classNode.name)) { // this
          className = instrumentor.classNode.name;
          List<FieldNode> fieldList =
              instrumentor.isStatic
                  ? new ArrayList<>()
                  : new ArrayList<>(instrumentor.classNode.fields);
          for (FieldNode fieldNode : fieldList) {
            if (fieldNode.name.equals(fieldName)) {
              if (isStaticField(fieldNode)) {
                continue; // or break?
              }
              fieldDesc = fieldNode.desc;
              if (fieldNode.signature != null) {
                returnType = decodeSignature(fieldNode.signature);
              } else {
                returnType = new ASMHelper.Type(Type.getType(fieldNode.desc));
              }
              if (useThisField) {
                insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
                // stack [this]
              }
              break;
            }
          }
        } else {
          className = currentType.getClassName();
          clazz =
              ensureSafeClassLoad(
                  className, getClassName(instrumentor.classNode.name), instrumentor.classLoader);
          Field declaredField = clazz.getDeclaredField(fieldName); // no parent fields!
          isAccessible = declaredField.isAccessible();
          fieldDesc = Type.getDescriptor(declaredField.getType());
          returnType = new ASMHelper.Type(Type.getType(declaredField.getType()));
        }
        if (fieldDesc == null) {
          return null;
        }
        // stack [target_object]
        insnList.add(new InsnNode(Opcodes.DUP));
        // stack [target_object, target_object]
        LabelNode nullNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFNULL, nullNode));
        if (isAccessible) {
          // stack [target_object]
          insnList.add(new FieldInsnNode(Opcodes.GETFIELD, className, fieldName, fieldDesc));
          // stack: [field_value]
        } else {
          ldc(insnList, fieldName);
          // stack: [target_object, string]
          emitReflectiveCall(insnList, returnType, OBJECT_TYPE);
          int sort = returnType.getMainType().getSort();
          if (sort == org.objectweb.asm.Type.OBJECT || sort == org.objectweb.asm.Type.ARRAY) {
            insnList.add(
                new TypeInsnNode(Opcodes.CHECKCAST, returnType.getMainType().getInternalName()));
          }
        }
        // build null branch which will be added later after the call to emit metric
        LabelNode gotoNode = new LabelNode();
        nullBranch.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
        nullBranch.add(nullNode);
        nullBranch.add(new InsnNode(Opcodes.POP)); // target_object
        nullBranch.add(new InsnNode(Opcodes.POP)); // metric name
        nullBranch.add(new InsnNode(Opcodes.POP)); // metric kind
        nullBranch.add(new InsnNode(Opcodes.POP)); // probeId
        nullBranch.add(gotoNode);
      } catch (Exception e) {
        String message = "Cannot resolve field " + fieldName;
        LOGGER.debug(message, e);
        throw new InvalidValueException(message);
      }
      return returnType;
    }

    private ASMHelper.Type tryRetrieveSynthetic(String name, InsnList insnList) {
      if (name.equals(ValueReferences.RETURN_REF)) {
        if (instrumentor.metricProbe.getEvaluateAt() != MethodLocation.EXIT) {
          return null;
        }
        if (returnContext == null) {
          return null;
        }
        VarInsnNode varInsnNode =
            new VarInsnNode(returnContext.opLoad, returnContext.returnLocalVarIdx);
        insnList.add(varInsnNode);
        // stack [return_value]
        return new ASMHelper.Type(returnContext.type);
      }
      if (name.equals(ValueReferences.DURATION_REF)) {
        if (instrumentor.metricProbe.getEvaluateAt() != MethodLocation.EXIT) {
          return null;
        }
        // call System.nanoTime at the beginning of the method
        if (durationStartVar == -1) {
          durationStartVar = instrumentor.newVar(LONG_TYPE);
          InsnList nanoTimeList = new InsnList();
          invokeStatic(nanoTimeList, Type.getType(System.class), "nanoTime", LONG_TYPE);
          nanoTimeList.add(new VarInsnNode(Opcodes.LSTORE, durationStartVar));
          durationStartLabel = new LabelNode();
          nanoTimeList.add(durationStartLabel);
          instrumentor.methodNode.instructions.insert(instrumentor.methodEnterLabel, nanoTimeList);
        }
        // diff nanoTime before calling metric
        invokeStatic(insnList, Type.getType(System.class), "nanoTime", LONG_TYPE);
        // stack [long]
        insnList.add(new VarInsnNode(Opcodes.LLOAD, durationStartVar));
        // stack [long, long]
        insnList.add(new InsnNode(Opcodes.LSUB));
        // stack [long]
        insnList.add(new InsnNode(Opcodes.L2D));
        // stack [double]
        insnList.add(new LdcInsnNode(1_000_000D));
        // stack [double, double]
        insnList.add(new InsnNode(Opcodes.DDIV));
        // stack [double]
        return new ASMHelper.Type(DOUBLE_TYPE);
      }
      return null;
    }
  }

  private static class ReturnContext {
    private final int returnLocalVarIdx;
    private final int opLoad;
    private final Type type;

    public ReturnContext(int returnLocalVarIdx, int opLoad, Type type) {
      this.returnLocalVarIdx = returnLocalVarIdx;
      this.opLoad = opLoad;
      this.type = type;
    }
  }
}
