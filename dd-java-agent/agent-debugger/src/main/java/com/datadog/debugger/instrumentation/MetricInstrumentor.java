package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.Types.*;
import static datadog.trace.bootstrap.ExceptionLogger.LOGGER;

import com.datadog.debugger.agent.MetricProbe;
import com.datadog.debugger.agent.ProbeDefinition;
import com.datadog.debugger.agent.Where;
import com.datadog.debugger.el.InvalidValueException;
import com.datadog.debugger.el.Literal;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Handles generating instrumentation for metric probes */
public class MetricInstrumentor extends Instrumentor {
  private static final InsnList EMPTY_INSN_LIST = new InsnList();
  private static final Pattern PERIOD_PATTERN = Pattern.compile("\\.");

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
      InsnList insnList = callMetric(metricProbe);
      methodNode.instructions.insert(methodEnterLabel, insnList);
    }
  }

  private InsnList callCount(MetricProbe metricProbe) {
    InsnList insnList = new InsnList();
    if (metricProbe.getValue() == null) {
      // consider the metric as an increment counter one
      insnList.add(new LdcInsnNode(metricProbe.getMetricName()));
      ldc(insnList, 1L); // stack [long]
      pushTags(insnList); // stack [long, array]
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
    if (result instanceof InsnListValue) {
      insnList.add(((InsnListValue) result).getValue());
    } else if (result instanceof Literal) {
      Object literal = result.getValue();
      if (literal instanceof Integer) {
        ldc(insnList, literal); // stack [int]
        insnList.add(new InsnNode(Opcodes.I2L)); // stack [long]
      } else if (literal instanceof Long) {
        ldc(insnList, literal); // stack [long]
      } else {
        reportError(
            "Unsupported literal: " + literal + " type: " + literal.getClass().getName() + ".");
        return EMPTY_INSN_LIST;
      }
    } else {
      reportError("Unsupported value expression.");
      return EMPTY_INSN_LIST;
    }
    // insert metric name at the beginning of the list
    insnList.insert(new LdcInsnNode(metricProbe.getMetricName())); // stack [string, long]
    pushTags(insnList); // stack [string, long, array]
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

      boolean isSingleLine = from == till;

      LabelNode beforeLabel = lineMap.getLineLabel(from);
      // single line N capture translates to line range (N, N+1)
      LabelNode afterLabel = lineMap.getLineLabel(till + (isSingleLine ? 1 : 0));
      if (beforeLabel == null && afterLabel == null) {
        reportError("No line info for " + (isSingleLine ? "line " : "range ") + sourceLine);
      }
      if (beforeLabel != null) {
        InsnList insnList = callMetric(metricProbe);
        methodNode.instructions.insertBefore(beforeLabel.getNext(), insnList);
      }
      if (afterLabel != null && !isSingleLine) {
        InsnList insnList = callMetric(metricProbe);
        methodNode.instructions.insert(afterLabel, insnList);
      }
    }
  }

  private void pushTags(InsnList insnList) {
    ProbeDefinition.Tag[] tags = metricProbe.getTags();
    if (tags == null || tags.length == 0) {
      insnList.add(new InsnNode(Opcodes.ACONST_NULL));
      return;
    }
    ldc(insnList, tags.length); // stack: [int]
    insnList.add(
        new TypeInsnNode(Opcodes.ANEWARRAY, STRING_TYPE.getInternalName())); // stack: [array]
    int counter = 0;
    for (ProbeDefinition.Tag tag : tags) {
      insnList.add(new InsnNode(Opcodes.DUP)); // stack: [array, array]
      ldc(insnList, counter++); // stack: [array, array, int]
      ldc(insnList, tag.toString()); // stack: [array, array, int, string]
      insnList.add(new InsnNode(Opcodes.AASTORE)); // stack: [array]
    }
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
    public Object resolve(String path) {
      String prefix = path.substring(0, 1);
      String[] parts = PERIOD_PATTERN.split(path.substring(1));
      InsnList insnList = new InsnList();
      String head = parts[0];
      Type currentType = null;
      if (prefix.equals(ValueReferences.FIELD_PREFIX)) {
        currentType = tryRetrieveField(head, insnList, parts.length == 1);
      } else if (prefix.equals(ValueReferences.LOCALVAR_PREFIX)) {
        currentType = tryRetrieveLocalVar(head, insnList, parts.length == 1);
      } else if (prefix.equals(ValueReferences.ARGUMENT_PREFIX)) {
        currentType = tryRetrieveArgument(head, insnList, parts.length == 1);
      }
      if (currentType == null) {
        return null;
      }
      if (followReferences(currentType, parts, insnList)) {
        return new InsnListValue(insnList);
      }
      return null;
    }

    private boolean followReferences(Type currentType, String[] parts, InsnList insnList) {
      // the iteration starts with index 1 as the index 0 was used to resolve the 'src' argument
      // in order to avoid extraneous array copies the original array is passed around as is
      for (int i = 1; i < parts.length; i++) {
        Class<?> clazz;
        try {
          String className = currentType.getClassName();
          clazz = Class.forName(className, true, classLoader);
          Field declaredField = clazz.getDeclaredField(parts[i]);
          String fieldDesc = Type.getDescriptor(declaredField.getType());
          currentType = Type.getType(declaredField.getType());
          if (i == parts.length - 1 && !isCompatible(currentType, expectedType)) {
            reportError(
                String.format(
                    "Incompatible type for field %s: %s with expected type: %s",
                    parts[i], currentType.getClassName(), expectedType.getClassName()));
            return false;
          }
          insnList.add(new InsnNode(Opcodes.DUP));
          LabelNode nullNode = new LabelNode();
          insnList.add(new JumpInsnNode(Opcodes.IFNULL, nullNode));
          insnList.add(
              new FieldInsnNode(
                  Opcodes.GETFIELD, className, parts[i], fieldDesc)); // stack: [field_value]
          convertIfRequired(currentType, expectedType, insnList);
          // build null branch which will be added later after the call to emit metric
          LabelNode gotoNode = new LabelNode();
          nullBranch.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
          nullBranch.add(nullNode);
          nullBranch.add(new InsnNode(Opcodes.POP));
          nullBranch.add(new InsnNode(Opcodes.POP));
          nullBranch.add(gotoNode);
        } catch (Exception e) {
          String message = "Cannot resolve field " + parts[i];
          LOGGER.debug(message, e);
          reportError(message);
          return false;
        }
      }
      return true;
    }

    private Type tryRetrieveArgument(String head, InsnList insnList, boolean isLast) {
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
          if (isLast && !isCompatible(argType, expectedType)) {
            reportError(
                String.format(
                    "Incompatible type for argument %s: %s with expected type: %s",
                    head, argType.getClassName(), expectedType.getClassName()));
            return null;
          }
          VarInsnNode varInsnNode = new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot);
          insnList.add(varInsnNode);
          convertIfRequired(argType, expectedType, insnList);
          return argType;
        }
        slot += argType.getSize();
      }
      reportError("Cannot resolve argument " + head);
      return null;
    }

    private Type tryRetrieveLocalVar(String head, InsnList insnList, boolean isLast) {
      for (LocalVariableNode varNode : methodNode.localVariables) {
        if (varNode.name.equals(head)) {
          Type varType = Type.getType(varNode.desc);
          if (isLast && !isCompatible(varType, expectedType)) {
            reportError(
                String.format(
                    "Incompatible type for local var %s: %s with expected type: %s",
                    head, varType.getClassName(), expectedType.getClassName()));
            return null;
          }
          VarInsnNode varInsnNode =
              new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), varNode.index);
          insnList.add(varInsnNode);
          convertIfRequired(varType, expectedType, insnList);
          return varType;
        }
      }
      reportError("Cannot resolve local var " + head);
      return null;
    }

    private Type tryRetrieveField(String head, InsnList insnList, boolean isLast) {
      List<FieldNode> fieldList = isStatic ? new ArrayList<>() : new ArrayList<>(classNode.fields);
      for (FieldNode fieldNode : fieldList) {
        if (fieldNode.name.equals(head)) {
          if (isStaticField(fieldNode)) {
            continue; // or break?
          }
          Type fieldType = Type.getType(fieldNode.desc);
          if (isLast && !isCompatible(fieldType, expectedType)) {
            reportError(
                String.format(
                    "Incompatible type for field %s: %s with expected type: %s",
                    head, fieldType.getClassName(), expectedType.getClassName()));
            return null;
          }
          insnList.add(new VarInsnNode(Opcodes.ALOAD, 0)); // stack: [this]
          insnList.add(
              new FieldInsnNode(
                  Opcodes.GETFIELD,
                  classNode.name,
                  fieldNode.name,
                  fieldNode.desc)); // stack: [field_value]
          convertIfRequired(fieldType, expectedType, insnList);
          return fieldType;
        }
      }
      reportError("Cannot resolve field " + head);
      return null;
    }

    private boolean isCompatible(Type argType, Type expectedType) {
      if (expectedType == Type.LONG_TYPE) {
        return argType == expectedType || argType == Type.INT_TYPE;
      }
      return argType == expectedType;
    }

    private void convertIfRequired(Type currentType, Type expectedType, InsnList insnList) {
      if (expectedType == Type.LONG_TYPE && currentType == Type.INT_TYPE) {
        insnList.add(new InsnNode(Opcodes.I2L));
      }
    }
  }
}
