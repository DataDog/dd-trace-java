package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.*;

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
import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.expressions.IsUndefinedExpression;
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
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.Where;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles generating instrumentation for metric probes */
public class MetricInstrumentor extends Instrumentor {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetricInstrumentor.class);
  private static final InsnList EMPTY_INSN_LIST = new InsnList();

  private final MetricProbe metricProbe;

  public MetricInstrumentor(
      MetricProbe metricProbe,
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {
    super(metricProbe, classLoader, classNode, methodNode, diagnostics, probeIds);
    this.metricProbe = metricProbe;
  }

  @Override
  public void instrument() {
    if (isLineProbe) {
      fillLineMap();
      addLineMetric(lineMap);
    } else {
      switch (definition.getEvaluateAt()) {
        case ENTRY:
        case DEFAULT:
          {
            InsnList insnList = callMetric(metricProbe);
            methodNode.instructions.insert(methodEnterLabel, insnList);
            break;
          }
        case EXIT:
          {
            processInstructions();
            break;
          }
        default:
          throw new IllegalArgumentException(
              "Invalid evaluateAt attribute: " + definition.getEvaluateAt());
      }
    }
  }

  @Override
  protected InsnList getBeforeReturnInsnList(AbstractInsnNode node) {
    return callMetric(metricProbe);
  }

  private InsnList callCount(MetricProbe metricProbe) {
    if (metricProbe.getValue() == null) {
      InsnList insnList = new InsnList();
      // consider the metric as an increment counter one
      insnList.add(new LdcInsnNode(metricProbe.getMetricName()));
      ldc(insnList, 1L); // stack [long]
      pushTags(
          insnList,
          addProbeIdWithTags(metricProbe.getId(), metricProbe.getTags())); // stack [long, array]
      invokeStatic(
          insnList,
          DEBUGGER_CONTEXT_TYPE,
          "count",
          Type.VOID_TYPE,
          STRING_TYPE,
          Type.LONG_TYPE,
          Types.asArray(STRING_TYPE, 1));
      return insnList;
    }
    return internalCallMetric("count", metricProbe);
  }

  private InsnList internalCallMetric(String metricMethodName, MetricProbe metricProbe) {
    InsnList insnList = new InsnList();
    insnList.add(new LdcInsnNode(metricProbe.getMetricName()));
    // stack [string]
    InsnList nullBranch = new InsnList();
    VisitorResult result;
    try {
      result = metricProbe.getValue().getExpr().accept(new MetricValueVisitor(this, nullBranch));
      // stack [string, int|long]
      convertIfRequired(result.type, Type.LONG_TYPE, result.insnList);
      // stack [string, long]
      insnList.add(result.insnList);
    } catch (InvalidValueException ex) {
      reportError(ex.getMessage());
      return EMPTY_INSN_LIST;
    }
    if (!isCompatible(result.type, Type.LONG_TYPE)) {
      reportError(
          String.format(
              "Incompatible type for expression: %s with expected type: %s",
              result.type.getClassName(), Type.LONG_TYPE.getClassName()));
      return EMPTY_INSN_LIST;
    }
    pushTags(insnList, addProbeIdWithTags(metricProbe.getId(), metricProbe.getTags()));
    // stack [string, long, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        metricMethodName,
        Type.VOID_TYPE,
        STRING_TYPE,
        Type.LONG_TYPE,
        Types.asArray(STRING_TYPE, 1));
    // stack []
    insnList.add(nullBranch);
    return insnList;
  }

  private void convertIfRequired(Type currentType, Type expectedType, InsnList insnList) {
    if (expectedType == Type.LONG_TYPE && currentType == Type.INT_TYPE) {
      insnList.add(new InsnNode(Opcodes.I2L));
    }
  }

  private InsnList callGauge(MetricProbe metricProbe) {
    if (metricProbe.getValue() == null) {
      return EMPTY_INSN_LIST;
    }
    return internalCallMetric("gauge", metricProbe);
  }

  private InsnList callHistogram(MetricProbe metricProbe) {
    if (metricProbe.getValue() == null) {
      return EMPTY_INSN_LIST;
    }
    return internalCallMetric("histogram", metricProbe);
  }

  private InsnList callMetric(MetricProbe metricProbe) {
    switch (metricProbe.getKind()) {
      case COUNT:
        return callCount(metricProbe);
      case GAUGE:
        return callGauge(metricProbe);
      case HISTOGRAM:
        return callHistogram(metricProbe);
      default:
        reportError(String.format("Unknown metric kind: %s", metricProbe.getKind()));
    }
    return null;
  }

  private void addLineMetric(LineMap lineMap) {
    Where.SourceLine[] targetLines = metricProbe.getWhere().getSourceLines();
    if (targetLines == null) {
      // no line capture to perform
      return;
    }
    if (lineMap.isEmpty()) {
      reportError("Missing line debug information.");
      return;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();
      LabelNode beforeLabel = lineMap.getLineLabel(from);
      // single line N capture translates to line range (N, N+1)
      LabelNode afterLabel = lineMap.getLineLabel(till + (sourceLine.isSingleLine() ? 1 : 0));
      if (beforeLabel == null && afterLabel == null) {
        reportError(
            "No line info for " + (sourceLine.isSingleLine() ? "line " : "range ") + sourceLine);
      }
      if (beforeLabel != null) {
        InsnList insnList = callMetric(metricProbe);
        methodNode.instructions.insertBefore(beforeLabel.getNext(), insnList);
      }
      if (afterLabel != null && !sourceLine.isSingleLine()) {
        InsnList insnList = callMetric(metricProbe);
        methodNode.instructions.insert(afterLabel, insnList);
      }
    }
  }

  private boolean isCompatible(Type argType, Type expectedType) {
    if (expectedType == Type.LONG_TYPE) {
      return argType == expectedType || argType == Type.INT_TYPE;
    }
    return argType == expectedType;
  }

  private static class VisitorResult {
    final Type type;
    final InsnList insnList;

    public VisitorResult(Type type, InsnList insnList) {
      this.type = type;
      this.insnList = insnList;
    }
  }

  private static class MetricValueVisitor implements Visitor<VisitorResult> {
    private final MetricInstrumentor instrumentor;
    private final InsnList nullBranch;

    public MetricValueVisitor(MetricInstrumentor instrumentor, InsnList nullBranch) {
      this.instrumentor = instrumentor;
      this.nullBranch = nullBranch;
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
    public VisitorResult visit(IsUndefinedExpression isUndefinedExpression) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(LenExpression lenExpression) {
      throw new UnsupportedOperationException();
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
      Type currentType;
      if (name.equals(ValueReferences.THIS)) {
        currentType = Type.getObjectType(instrumentor.classNode.name);
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // stack [this]
      } else {
        currentType = tryRetrieve(name, insnList);
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
      Type type = tryRetrieveField(result.type, name, result.insnList, false);
      if (type == null) {
        throw new InvalidValueException("Cannot resolve symbol " + name);
      }
      // stack [field]
      return new VisitorResult(type, result.insnList);
    }

    @Override
    public VisitorResult visit(IndexExpression indexExpression) {
      throw new UnsupportedOperationException();
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
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(NumericValue numericValue) {
      Number number = numericValue.getValue();
      InsnList insnList = new InsnList();
      if (number instanceof Long) {
        ldc(insnList, number.longValue());
        return new VisitorResult(Type.LONG_TYPE, insnList);
      }
      throw new InvalidValueException(
          "Unsupported constant value: " + number + " type: " + number.getClass().getTypeName());
    }

    @Override
    public VisitorResult visit(BooleanValue booleanValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(ListValue listValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VisitorResult visit(MapValue mapValue) {
      throw new UnsupportedOperationException();
    }

    private Type tryRetrieve(String name, InsnList insnList) {
      Type result = tryRetrieveArgument(name, insnList);
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

    private Type tryRetrieveArgument(String head, InsnList insnList) {
      Type[] argTypes = Type.getArgumentTypes(instrumentor.methodNode.desc);
      if (argTypes.length == 0) {
        // bail out if no args
        return null;
      }
      int counter = 0;
      int slot = instrumentor.isStatic ? 0 : 1;
      for (Type argType : argTypes) {
        String currentArgName = null;
        if (instrumentor.localVarsBySlot.length > 0) {
          LocalVariableNode localVarNode = instrumentor.localVarsBySlot[slot];
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
          return argType;
        }
        slot += argType.getSize();
      }
      return null;
    }

    private Type tryRetrieveLocalVar(String head, InsnList insnList) {
      for (LocalVariableNode varNode : instrumentor.methodNode.localVariables) {
        if (varNode.name.equals(head)) {
          Type varType = Type.getType(varNode.desc);
          VarInsnNode varInsnNode =
              new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), varNode.index);
          insnList.add(varInsnNode);
          // stack [local]
          return varType;
        }
      }
      return null;
    }

    private Type tryRetrieveField(
        Type currentType, String fieldName, InsnList insnList, boolean useThisField) {
      Class<?> clazz;
      try {
        String className;
        String fieldDesc = null;
        if (currentType.getClassName().equals(instrumentor.classNode.name)) { // this
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
              currentType = Type.getType(fieldNode.desc);
              if (useThisField) {
                insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
                // stack [this]
              }
              break;
            }
          }
        } else {
          className = currentType.getClassName();
          clazz = Class.forName(className, true, instrumentor.classLoader);
          Field declaredField = clazz.getDeclaredField(fieldName); // no parent fields!
          fieldDesc = Type.getDescriptor(declaredField.getType());
          currentType = Type.getType(declaredField.getType());
        }
        if (fieldDesc == null) {
          return null;
        }
        // stack [target_object]
        insnList.add(new InsnNode(Opcodes.DUP));
        // stack [target_object, target_object]
        LabelNode nullNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFNULL, nullNode));
        // stack [target_object]
        insnList.add(new FieldInsnNode(Opcodes.GETFIELD, className, fieldName, fieldDesc));
        // stack: [field_value]
        // build null branch which will be added later after the call to emit metric
        LabelNode gotoNode = new LabelNode();
        nullBranch.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
        nullBranch.add(nullNode);
        nullBranch.add(new InsnNode(Opcodes.POP));
        nullBranch.add(new InsnNode(Opcodes.POP));
        nullBranch.add(gotoNode);
      } catch (Exception e) {
        String message = "Cannot resolve field " + fieldName;
        LOGGER.debug(message, e);
        throw new InvalidValueException(message);
      }
      return currentType;
    }
  }
}
