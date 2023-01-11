package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.*;

import com.datadog.debugger.el.InvalidValueException;
import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.UndefinedValue;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.Where;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
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
      List<DiagnosticMessage> diagnostics) {
    super(metricProbe, classLoader, classNode, methodNode, diagnostics);
    this.metricProbe = metricProbe;
  }

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
    InsnList insnList = new InsnList();
    if (metricProbe.getValue() == null) {
      // consider the metric as an increment counter one
      insnList.add(new LdcInsnNode(metricProbe.getMetricName()));
      ldc(insnList, 1L); // stack [long]
      pushTags(insnList, metricProbe.getTags()); // stack [long, array]
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
    return internalCallMetric("count", metricProbe, insnList);
  }

  private InsnList internalCallMetric(
      String metricMethodName, MetricProbe metricProbe, InsnList insnList) {
    InsnList nullBranch = new InsnList();
    try {
      metricProbe
          .getValue()
          .execute(new CompileToInsnList(classNode, methodNode, Type.LONG_TYPE, nullBranch));
    } catch (InvalidValueException ex) {
      reportError(ex.getMessage());
      return EMPTY_INSN_LIST;
    }
    Value<?> result = metricProbe.getValue().getResult();
    if (result.isNull() || result.isUndefined()) {
      return EMPTY_INSN_LIST;
    }
    if (result instanceof ObjectValue) {
      ResolverResult resolverResult = (ResolverResult) ((ObjectValue) result).getValue();
      if (!isCompatible(resolverResult.type, Type.LONG_TYPE)) {
        reportError(
            String.format(
                "Incompatible type for expression: %s with expected type: %s",
                resolverResult.type.getClassName(), Type.LONG_TYPE.getClassName()));
        return EMPTY_INSN_LIST;
      }
      insnList.add(resolverResult.insnList);
    } else if (result instanceof Literal) {
      Object literal = result.getValue();
      if (literal instanceof Integer) {
        ldc(insnList, literal); // stack [int]
        insnList.add(new InsnNode(Opcodes.I2L)); // stack [long]
      } else if (literal instanceof Long) {
        ldc(insnList, literal); // stack [long]
      } else {
        reportError(
            "Unsupported literal: "
                + literal
                + " type: "
                + literal.getClass().getTypeName()
                + ", expect integral type (int, long).");
        return EMPTY_INSN_LIST;
      }
    } else {
      reportError("Unsupported value expression.");
      return EMPTY_INSN_LIST;
    }
    // insert metric name at the beginning of the list
    insnList.insert(new LdcInsnNode(metricProbe.getMetricName())); // stack [string, long]
    pushTags(insnList, metricProbe.getTags()); // stack [string, long, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        metricMethodName,
        Type.VOID_TYPE,
        STRING_TYPE,
        Type.LONG_TYPE,
        Types.asArray(STRING_TYPE, 1));
    insnList.add(nullBranch);
    return insnList;
  }

  private InsnList callGauge(MetricProbe metricProbe) {
    if (metricProbe.getValue() == null) {
      return EMPTY_INSN_LIST;
    }
    InsnList insnList = new InsnList();
    return internalCallMetric("gauge", metricProbe, insnList);
  }

  private InsnList callHistogram(MetricProbe metricProbe) {
    if (metricProbe.getValue() == null) {
      return EMPTY_INSN_LIST;
    }
    InsnList insnList = new InsnList();
    return internalCallMetric("histogram", metricProbe, insnList);
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

  private class CompileToInsnList implements ValueReferenceResolver {
    private final ClassNode classNode;
    private final MethodNode methodNode;
    private final Type expectedType;
    private final InsnList nullBranch;

    public CompileToInsnList(
        ClassNode classNode, MethodNode methodNode, Type expectedType, InsnList nullBranch) {
      this.classNode = classNode;
      this.methodNode = methodNode;
      this.expectedType = expectedType;
      this.nullBranch = nullBranch;
    }

    @Override
    public Object lookup(String name) {
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException("empty name for lookup operation");
      }
      InsnList insnList = new InsnList();
      Type currentType;
      String rawName = name;
      if (name.startsWith(ValueReferences.FIELD_PREFIX)) {
        rawName = name.substring(ValueReferences.FIELD_PREFIX.length());
        currentType = tryRetrieveField(rawName, insnList);
      } else {
        currentType = tryRetrieve(name, insnList);
      }
      if (currentType == null) {
        reportError("Cannot resolve symbol " + rawName);
        return null;
      }
      convertIfRequired(currentType, expectedType, insnList);
      return new ResolverResult(currentType, insnList);
    }

    @Override
    public Object getMember(Object target, String name) {
      if (target instanceof ResolverResult) {
        ResolverResult result = (ResolverResult) target;
        Type currentType = followReferences(result.type, name, result.insnList);
        if (currentType != null) {
          return new ObjectValue(new ResolverResult(currentType, result.insnList));
        }
      }
      return UndefinedValue.INSTANCE;
    }

    private Type followReferences(Type currentType, String name, InsnList insnList) {
      Class<?> clazz;
      try {
        String className = currentType.getClassName();
        clazz = Class.forName(className, true, classLoader);
        Field declaredField = clazz.getDeclaredField(name); // no parent fields!
        String fieldDesc = Type.getDescriptor(declaredField.getType());
        currentType = Type.getType(declaredField.getType());
        insnList.add(new InsnNode(Opcodes.DUP));
        LabelNode nullNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFNULL, nullNode));
        insnList.add(
            new FieldInsnNode(
                Opcodes.GETFIELD, className, name, fieldDesc)); // stack: [field_value]
        convertIfRequired(currentType, expectedType, insnList);
        // build null branch which will be added later after the call to emit metric
        LabelNode gotoNode = new LabelNode();
        nullBranch.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
        nullBranch.add(nullNode);
        nullBranch.add(new InsnNode(Opcodes.POP));
        nullBranch.add(new InsnNode(Opcodes.POP));
        nullBranch.add(gotoNode);
      } catch (Exception e) {
        String message = "Cannot resolve field " + name;
        LOGGER.debug(message, e);
        reportError(message);
        return null;
      }
      return currentType;
    }

    private Type tryRetrieve(String head, InsnList insnList) {
      Type result = tryRetrieveArgument(head, insnList);
      if (result != null) {
        return result;
      }
      result = tryRetrieveLocalVar(head, insnList);
      if (result != null) {
        return result;
      }
      return tryRetrieveField(head, insnList);
    }

    private Type tryRetrieveArgument(String head, InsnList insnList) {
      Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
      if (argTypes.length == 0) {
        // bail out if no args
        return null;
      }
      int counter = 0;
      int slot = isStatic ? 0 : 1;
      for (Type argType : argTypes) {
        String currentArgName = argumentNames[slot];
        if (currentArgName == null) {
          // if argument names are not resolved correctly let's assign p+arg_index
          currentArgName = "p" + counter;
        }
        if (currentArgName.equals(head)) {
          VarInsnNode varInsnNode = new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot);
          insnList.add(varInsnNode);
          return argType;
        }
        slot += argType.getSize();
      }
      return null;
    }

    private Type tryRetrieveLocalVar(String head, InsnList insnList) {
      for (LocalVariableNode varNode : methodNode.localVariables) {
        if (varNode.name.equals(head)) {
          Type varType = Type.getType(varNode.desc);
          VarInsnNode varInsnNode =
              new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), varNode.index);
          insnList.add(varInsnNode);
          return varType;
        }
      }
      return null;
    }

    private Type tryRetrieveField(String head, InsnList insnList) {
      List<FieldNode> fieldList = isStatic ? new ArrayList<>() : new ArrayList<>(classNode.fields);
      for (FieldNode fieldNode : fieldList) {
        if (fieldNode.name.equals(head)) {
          if (isStaticField(fieldNode)) {
            continue; // or break?
          }
          Type fieldType = Type.getType(fieldNode.desc);
          insnList.add(new VarInsnNode(Opcodes.ALOAD, 0)); // stack: [this]
          insnList.add(
              new FieldInsnNode(
                  Opcodes.GETFIELD,
                  classNode.name,
                  fieldNode.name,
                  fieldNode.desc)); // stack: [field_value]
          return fieldType;
        }
      }
      return null;
    }

    private void convertIfRequired(Type currentType, Type expectedType, InsnList insnList) {
      if (expectedType == Type.LONG_TYPE && currentType == Type.INT_TYPE) {
        insnList.add(new InsnNode(Opcodes.I2L));
      }
    }
  }

  private static class ResolverResult {
    final Type type;
    final InsnList insnList;

    public ResolverResult(Type type, InsnList insnList) {
      this.type = type;
      this.insnList = insnList;
    }
  }
}
