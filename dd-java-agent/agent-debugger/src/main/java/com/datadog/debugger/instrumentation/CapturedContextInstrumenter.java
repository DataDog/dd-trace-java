package com.datadog.debugger.instrumentation;

import static com.datadog.debugger.instrumentation.ASMHelper.getStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeConstructor;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeStatic;
import static com.datadog.debugger.instrumentation.ASMHelper.invokeVirtual;
import static com.datadog.debugger.instrumentation.ASMHelper.isFinalField;
import static com.datadog.debugger.instrumentation.ASMHelper.isStaticField;
import static com.datadog.debugger.instrumentation.ASMHelper.ldc;
import static com.datadog.debugger.instrumentation.ASMHelper.newInstance;
import static com.datadog.debugger.instrumentation.Types.CAPTURED_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.CAPTURED_VALUE;
import static com.datadog.debugger.instrumentation.Types.CAPTURE_THROWABLE_TYPE;
import static com.datadog.debugger.instrumentation.Types.CLASS_TYPE;
import static com.datadog.debugger.instrumentation.Types.DEBUGGER_CONTEXT_TYPE;
import static com.datadog.debugger.instrumentation.Types.INT_ARRAY_TYPE;
import static com.datadog.debugger.instrumentation.Types.METHOD_LOCATION_TYPE;
import static com.datadog.debugger.instrumentation.Types.OBJECT_TYPE;
import static com.datadog.debugger.instrumentation.Types.REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE;
import static com.datadog.debugger.instrumentation.Types.STRING_TYPE;
import static com.datadog.debugger.instrumentation.Types.THROWABLE_TYPE;
import static org.objectweb.asm.Type.BOOLEAN_TYPE;
import static org.objectweb.asm.Type.INT_TYPE;
import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getType;

import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassFileLines;
import com.datadog.debugger.util.JvmLanguage;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CapturedContextInstrumenter extends Instrumenter {
  private static final Logger LOGGER = LoggerFactory.getLogger(CapturedContextInstrumenter.class);
  private final boolean captureSnapshot;
  private final boolean captureEntry;
  private final Limits limits;
  protected final LabelNode contextInitLabel = new LabelNode();
  private int entryContextVar = -1;
  private int exitContextVar = -1;
  private int timestampStartVar = -1;
  private int throwableListVar = -1;
  private Collection<LocalVariableNode> hoistedLocalVars = Collections.emptyList();

  public CapturedContextInstrumenter(
      ProbeDefinition definition,
      MethodInfo methodInfo,
      List<DiagnosticMessage> diagnostics,
      List<Integer> probeIndices,
      boolean captureSnapshot,
      boolean captureEntry,
      Limits limits) {
    super(definition, methodInfo, diagnostics, probeIndices);
    this.captureSnapshot = captureSnapshot;
    this.captureEntry = captureEntry;
    this.limits = limits;
  }

  @Override
  public InstrumentationResult.Status instrument() {
    if (definition.isLineProbe()) {
      if (!addLineCaptures(classFileLines)) {
        return InstrumentationResult.Status.ERROR;
      }
      installFinallyBlocks();
      return InstrumentationResult.Status.INSTALLED;
    }
    instrumentMethodEnter();
    instrumentTryCatchHandlers();
    processInstructions();
    addFinallyHandler(contextInitLabel, returnHandlerLabel);
    installFinallyBlocks();
    return InstrumentationResult.Status.INSTALLED;
  }

  private boolean addLineCaptures(ClassFileLines classFileLines) {
    Where.SourceLine[] targetLines = definition.getWhere().getSourceLines();
    if (targetLines == null) {
      reportError("Missing line(s) in probe definition.");
      return false;
    }
    if (classFileLines.isEmpty()) {
      reportError("Missing line debug information.");
      return false;
    }
    for (Where.SourceLine sourceLine : targetLines) {
      int from = sourceLine.getFrom();
      int till = sourceLine.getTill();

      boolean isSingleLine = from == till;

      LabelNode beforeLabel = classFileLines.getLineLabel(from);
      // single line N capture translates to line range (N, N+1)
      LabelNode afterLabel = classFileLines.getLineLabel(till + (isSingleLine ? 1 : 0));
      if (beforeLabel == null && afterLabel == null) {
        reportError("No line info for " + (isSingleLine ? "line " : "range ") + sourceLine + ".");
      }
      if (beforeLabel != null) {
        InsnList insnList = new InsnList();
        if (isSingleLine) {
          TryCatchBlockNode catchHandler = findCatchHandler(beforeLabel);
          if (catchHandler != null && !isExceptionLocalDeclared(catchHandler, methodNode)) {
            // empty catch block does not declare exception local variable - we add it
            int idx = addExceptionLocal(catchHandler, methodNode);
            // store exception in the new local variable
            // stack [exception]
            insnList.add(new InsnNode(Opcodes.DUP));
            // stack [exception, exception]
            insnList.add(new VarInsnNode(Opcodes.ASTORE, idx));
            // stack [exception]
          }
        }
        addIsReadyToCaptureCall(insnList);
        // stack [boolean]
        LabelNode targetNode = new LabelNode();
        insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
        LabelNode inProbeStartLabel = new LabelNode();
        insnList.add(inProbeStartLabel);
        // stack []
        addEvalContextAndCommitCall(sourceLine, insnList, beforeLabel);
        // stack []
        invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
        LabelNode inProbeEndLabel = new LabelNode();
        insnList.add(inProbeEndLabel);
        createInProbeFinallyHandler(inProbeStartLabel, inProbeEndLabel);
        insnList.add(targetNode);
        methodNode.instructions.insertBefore(beforeLabel.getNext(), insnList);
      }
      if (afterLabel != null && !isSingleLine) {
        InsnList insnList = collectCapturedContext(Snapshot.Kind.AFTER, afterLabel);
        methodNode.instructions.insert(afterLabel, insnList);
      }
    }
    return true;
  }

  protected void addEvalContextAndCommitCall(
      Where.SourceLine sourceLine, InsnList insnList, LabelNode beforeLabel) {
    insnList.add(collectCapturedContext(Snapshot.Kind.BEFORE, beforeLabel));
    // stack [capturedcontext]
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [capturedcontext, class]
    ldc(insnList, sourceLine.getFrom());
    // stack [capturedcontext, class, int]
    pushProbeIndices(insnList);
    // stack [capturedcontext, class, int, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "evalContextAndCommit",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CLASS_TYPE,
        INT_TYPE,
        INT_ARRAY_TYPE);
    // stack []
  }

  private int addExceptionLocal(TryCatchBlockNode catchHandler, MethodNode methodNode) {
    AbstractInsnNode current = catchHandler.handler;
    while (current != null
        && (current.getType() == AbstractInsnNode.LABEL
            || current.getType() == AbstractInsnNode.LINE)) {
      current = current.getNext();
    }
    if (current == null) {
      reportWarning("Cannot add exception local variable to catch block - no instructions.");
      return -1;
    }
    if (current.getOpcode() != Opcodes.ASTORE) {
      reportWarning("Cannot add exception local variable to catch block - no store instruction.");
      return -1;
    }
    int exceptionLocalIdx = ((VarInsnNode) current).var;
    Set<String> localNames =
        methodNode.localVariables.stream()
            .map(localVariableNode -> localVariableNode.name)
            .collect(Collectors.toSet());
    // find next label assume this is the end of the handler
    while (current != null && current.getType() != AbstractInsnNode.LABEL) {
      current = current.getNext();
    }
    if (current == null) {
      reportWarning("Cannot add exception local variable to catch block - no end of handler.");
      return -1;
    }
    LabelNode end = (LabelNode) current;
    for (int i = 0; i < 100; i++) {
      String exceptionLocalName = "ex" + i;
      if (!localNames.contains(exceptionLocalName)) {
        methodNode.localVariables.add(
            new LocalVariableNode(
                exceptionLocalName,
                "L" + catchHandler.type + ";",
                null,
                catchHandler.handler,
                end,
                exceptionLocalIdx));
        return exceptionLocalIdx;
      }
    }
    return -1;
  }

  private TryCatchBlockNode findCatchHandler(LabelNode targetLine) {
    for (TryCatchBlockNode tryCatchBlockNode : methodNode.tryCatchBlocks) {
      if (tryCatchBlockNode.handler == targetLine) {
        return tryCatchBlockNode;
      }
    }
    return null;
  }

  private boolean isExceptionLocalDeclared(TryCatchBlockNode catchHandler, MethodNode methodNode) {
    if (methodNode.localVariables == null || methodNode.localVariables.isEmpty()) {
      return false;
    }
    String catchDesc =
        catchHandler.type != null ? "L" + catchHandler.type + ";" : "Ljava/lang/Throwable;";
    for (LocalVariableNode local : methodNode.localVariables) {
      if (catchDesc.equals(local.desc)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected InsnList getBeforeReturnInsnList(AbstractInsnNode node) {
    InsnList insnList = new InsnList();
    // stack [ret_value]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, entryContextVar));
    // stack [ret_value, capturedcontext]
    LabelNode targetNode = new LabelNode();
    LabelNode gotoNode = new LabelNode();
    invokeVirtual(insnList, CAPTURED_CONTEXT_TYPE, "isCapturing", BOOLEAN_TYPE);
    // stack [ret_value, boolean]
    insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
    // stack [ret_value]
    addEvalContextCall(insnList, Snapshot.Kind.RETURN, node, timestampStartVar, "EXIT");
    // stack [ret_value]
    invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    insnList.add(targetNode);
    getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CONTEXT");
    // stack [ret_value, capturedcontext]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, exitContextVar));
    // stack [ret_value]
    insnList.add(gotoNode);
    return insnList;
  }

  @Override
  protected InsnList getReturnHandlerInsnList() {
    return commit();
  }

  private InsnList commit() {
    InsnList insnList = new InsnList();
    // stack []
    if (entryContextVar != -1) {
      getContext(insnList, entryContextVar);
    } else {
      getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CAPTURING_CONTEXT");
    }
    // stack [capturedcontext]
    getContext(insnList, exitContextVar);
    // stack [capturedcontext, capturedcontext]
    if (throwableListVar != -1) {
      insnList.add(new VarInsnNode(Opcodes.ALOAD, throwableListVar));
    } else {
      insnList.add(new InsnNode(Opcodes.ACONST_NULL));
    }
    // stack [capturedcontext, capturedcontext, list]
    addCommitCall(insnList);
    // stack []
    return insnList;
  }

  protected void addCommitCall(InsnList insnList) {
    // stack [capturedcontext, capturedcontext, list]
    pushProbeIndices(insnList);
    // stack [capturedcontext, capturedcontext, list, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "commit",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CAPTURED_CONTEXT_TYPE,
        getType(List.class),
        INT_ARRAY_TYPE);
    // stack []
  }

  protected void addFinallyHandler(LabelNode startLabel, LabelNode endLabel) {
    // stack: [exception]
    if (methodNode.tryCatchBlocks == null) {
      methodNode.tryCatchBlocks = new ArrayList<>();
    }
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // stack [exception]
    LabelNode targetNode = null;
    if (entryContextVar != -1) {
      handler.add(new VarInsnNode(Opcodes.ALOAD, entryContextVar));
      // stack [exception, capturedcontext]
      targetNode = new LabelNode();
      invokeVirtual(handler, CAPTURED_CONTEXT_TYPE, "isCapturing", BOOLEAN_TYPE);
      // stack [exception, boolean]
      handler.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
    }
    if (exitContextVar == -1) {
      exitContextVar = newVar(CAPTURED_CONTEXT_TYPE);
    }
    // stack [exception]
    addEvalContextCall(
        handler, Snapshot.Kind.UNHANDLED_EXCEPTION, endLabel, timestampStartVar, "EXIT");
    // stack [exception]
    invokeStatic(handler, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    // stack [exception]
    handler.add(commit());
    if (targetNode != null) {
      handler.add(targetNode);
    }
    // stack [exception]
    handler.add(new InsnNode(Opcodes.ATHROW));
    // stack: []
    methodNode.instructions.add(handler);
    finallyBlocks.add(new FinallyBlock(startLabel, endLabel, handlerLabel));
  }

  protected void addEvalContextCall(
      InsnList insnList,
      Snapshot.Kind snapshotKind,
      AbstractInsnNode endLabel,
      int timestampVar,
      String methodLocation) {
    // stack []
    insnList.add(collectCapturedContext(snapshotKind, endLabel));
    // stack: [capturedcontext]
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [capturedcontext, class]
    if (timestampVar != -1) {
      insnList.add(new VarInsnNode(Opcodes.LLOAD, timestampVar));
    } else {
      ldc(insnList, -1L);
    }
    // stack [capturedcontext, class, long]
    getStatic(insnList, METHOD_LOCATION_TYPE, methodLocation);
    // stack [capturedcontext, class, long, methodlocation]
    pushProbeIndices(insnList);
    // stack [capturedcontext, class, long, methodlocation, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "evalContext",
        VOID_TYPE,
        CAPTURED_CONTEXT_TYPE,
        CLASS_TYPE,
        LONG_TYPE,
        METHOD_LOCATION_TYPE,
        INT_ARRAY_TYPE);
    // stack []
  }

  private void instrumentMethodEnter() {
    InsnList insnList = new InsnList();
    entryContextVar = declareContextVar(insnList);
    exitContextVar = declareContextVar(insnList);
    timestampStartVar = declareTimestampVar(insnList);
    if (methodNode.tryCatchBlocks.size() > 0) {
      throwableListVar = declareThrowableList(insnList);
    }
    hoistedLocalVars = initAndHoistLocalVars(methodNode);
    insnList.add(contextInitLabel);
    if (definition instanceof SpanDecorationProbe
        && definition.getEvaluateAt() == MethodLocation.EXIT) {
      // if evaluation is at exit for a span decoration probe, skip collecting data at enter
      methodNode.instructions.insert(methodEnterLabel, insnList);
      return;
    }
    addIsReadyToCaptureCall(insnList);
    LabelNode targetNode = new LabelNode();
    LabelNode gotoNode = new LabelNode();
    insnList.add(new JumpInsnNode(Opcodes.IFEQ, targetNode));
    if (captureEntry) {
      LabelNode inProbeStartLabel = new LabelNode();
      insnList.add(inProbeStartLabel);
      // stack []
      addEvalContextCall(insnList, Snapshot.Kind.ENTER, null, -1, "ENTRY");
      // stack []
      invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
      LabelNode inProbeEndLabel = new LabelNode();
      insnList.add(inProbeEndLabel);
      createInProbeFinallyHandler(inProbeStartLabel, inProbeEndLabel);
    } else {
      invokeStatic(insnList, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    }
    // stack []
    insnList.add(new JumpInsnNode(Opcodes.GOTO, gotoNode));
    insnList.add(targetNode);
    getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CONTEXT");
    // stack [capturedcontext]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, entryContextVar));
    // stack []
    insnList.add(gotoNode);
    methodNode.instructions.insert(methodEnterLabel, insnList);
  }

  protected void addIsReadyToCaptureCall(InsnList insnList) {
    ldc(insnList, Type.getObjectType(classNode.name));
    // stack [class]
    pushProbeIndices(insnList);
    // stack [class, array]
    invokeStatic(
        insnList,
        DEBUGGER_CONTEXT_TYPE,
        "isReadyToCapture",
        Type.BOOLEAN_TYPE,
        CLASS_TYPE,
        INT_ARRAY_TYPE);
    // stack [boolean]
  }

  // Initialize and hoist local variables to the top of the method
  // if there is name/slot conflict, do nothing for the conflicting local variable
  private Collection<LocalVariableNode> initAndHoistLocalVars(MethodNode methodNode) {
    int hoistingLevel = Config.get().getDynamicInstrumentationLocalVarHoistingLevel();
    if (hoistingLevel == 0 || language != JvmLanguage.JAVA) {
      // for now, only hoist local vars for Java
      return Collections.emptyList();
    }
    if (methodNode.localVariables == null || methodNode.localVariables.isEmpty()) {
      return Collections.emptyList();
    }
    LOGGER.debug(
        "Hoisting local variables level={} for method: {}", hoistingLevel, methodNode.name);
    return LocalVarHoisting.processMethod(methodNode, hoistingLevel);
  }

  private void createInProbeFinallyHandler(LabelNode inProbeStartLabel, LabelNode inProbeEndLabel) {
    LabelNode handlerLabel = new LabelNode();
    InsnList handler = new InsnList();
    handler.add(handlerLabel);
    // stack [exception]
    invokeStatic(handler, DEBUGGER_CONTEXT_TYPE, "disableInProbe", VOID_TYPE);
    // stack [exception]
    handler.add(new InsnNode(Opcodes.ATHROW));
    methodNode.instructions.add(handler);
    finallyBlocks.add(new FinallyBlock(inProbeStartLabel, inProbeEndLabel, handlerLabel));
  }

  private void pushProbeIndices(InsnList insnList) {
    ldc(insnList, probeIndices.size()); // array size
    // stack [int]
    insnList.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
    // stack [array]
    for (int i = 0; i < probeIndices.size(); i++) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack [array, array]
      ldc(insnList, i); // index
      // stack [array, array, int]
      ldc(insnList, probeIndices.get(i).intValue());
      // stack [array, array, int, int]
      insnList.add(new InsnNode(Opcodes.IASTORE));
      // stack [array]
    }
  }

  private void instrumentTryCatchHandlers() {
    if (!captureSnapshot) {
      // do not instrument try/catch for log probe
      return;
    }
    for (TryCatchBlockNode tryCatchBlockNode : methodNode.tryCatchBlocks) {
      // stack [throwable]
      InsnList insnList = new InsnList();
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack [throwable, throwable]
      newInstance(insnList, CAPTURE_THROWABLE_TYPE);
      // stack [throwable, throwable, capturedthrowable]
      insnList.add(new InsnNode(Opcodes.DUP_X1));
      // stack [throwable, capturedthrowable, throwable, capturedthrowable]
      insnList.add(new InsnNode(Opcodes.SWAP));
      // stack [throwable, capturedthrowable, capturedthrowable, throwable]
      invokeConstructor(insnList, CAPTURE_THROWABLE_TYPE, THROWABLE_TYPE);
      // stack [throwable, capturedthrowable]
      addToThrowableList(insnList);
      // stack [throwable]
      methodNode.instructions.insert(tryCatchBlockNode.handler, insnList);
    }
  }

  private void addToThrowableList(InsnList insnList) {
    // stack [capturedthrowable]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, throwableListVar));
    // stack [capturedthrowable, list]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [capturedthrowable, list, list]
    LabelNode targetNode = new LabelNode();
    insnList.add(new JumpInsnNode(Opcodes.IFNONNULL, targetNode));
    insnList.add(new InsnNode(Opcodes.POP));
    // stack [capturedthrowable]
    newInstance(insnList, getType(ArrayList.class));
    // stack [capturedthrowable, list]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [capturedthrowable, list, list]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack [capturedthrowable, list, list, list]
    invokeConstructor(insnList, getType(ArrayList.class));
    // stack [capturedthrowable, list, list]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, throwableListVar));
    // stack [capturedthrowable, list]
    insnList.add(targetNode);
    // stack [capturedthrowable, list]
    insnList.add(new InsnNode(Opcodes.SWAP));
    // stack [list, capturedthrowable]
    invokeVirtual(insnList, getType(ArrayList.class), "add", BOOLEAN_TYPE, OBJECT_TYPE);
    // stack [boolean]
    insnList.add(new InsnNode(Opcodes.POP));
    // stack []
  }

  protected InsnList collectCapturedContext(Snapshot.Kind kind, AbstractInsnNode location) {
    InsnList insnList = new InsnList();
    if (kind == Snapshot.Kind.ENTER) {
      createContext(insnList, entryContextVar);
    } else if (kind == Snapshot.Kind.RETURN) {
      createContext(insnList, exitContextVar);
    } else if (kind == Snapshot.Kind.BEFORE) {
      createContext(insnList, -1);
    } else if (kind == Snapshot.Kind.UNHANDLED_EXCEPTION) {
      createContext(insnList, exitContextVar);
    } else {
      throw new IllegalArgumentException("kind not supported " + kind);
    }
    // stack: [capturedcontext]
    collectArguments(insnList, kind);
    // stack: [capturedcontext]
    collectStaticFields(insnList);
    // stack: [capturedcontext]
    /*
     * It makes no sense collecting local variables for exceptions - the ones contributing to the exception
     * are most likely to be outside of the scope in the exception handler block and there is no way to figure
     * out the originating location just from bytecode.
     *
     * However, it is very useful, in particular for Exception Debugging (Replay) to collect local variables.
     * Thus, we are hoisting the local variable scope by initializing them at the beginning of the method
     * with the method initLocalVars. It allows us to collect local variables at any point.
     */
    collectLocalVariables(location, insnList);
    // stack: [capturedcontext]
    if (kind == Snapshot.Kind.RETURN) {
      collectReturnValue(location, insnList);
      // stack: [capturedcontext]
    } else if (kind == Snapshot.Kind.UNHANDLED_EXCEPTION) {
      collectExceptionValue(location, insnList);
      // stack: [capturedcontext]
    }
    return insnList;
  }

  private void collectArguments(InsnList insnList, Snapshot.Kind kind) {
    // expected stack top: [capturedcontext]
    Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
    if (argTypes.length == 0 && isStatic) {
      // bail out if no args
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, argTypes.length + (isStatic ? 0 : 1));
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    if (!isStatic) {
      captureThis(insnList);
      // stack: [capturedcontext, capturedcontext, array]
    }
    int counter = isStatic ? 0 : 1;
    int slot = isStatic ? 0 : 1;
    for (Type argType : argTypes) {
      String currentArgName = null;
      if (slot < localVarsBySlotArray.length) {
        LocalVariableNode localVarNode = localVarsBySlotArray[slot];
        currentArgName = localVarNode != null ? localVarNode.name : null;
      }
      if (currentArgName == null) {
        // if argument names are not resolved correctly let's assign p+arg_index
        currentArgName = "p" + (counter - (isStatic ? 0 : 1));
      }
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(insnList, currentArgName);
      // stack: [capturedcontext, capturedcontext, array, array, int, string]
      ldc(insnList, argType.getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
      if (Redaction.isRedactedKeyword(currentArgName)) {
        addCapturedValueRedacted(insnList);
      } else {
        insnList.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot));
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, arg]
        tryBox(argType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, captured_value]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
      slot += argType.getSize();
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addArguments",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
  }

  private void captureThis(InsnList insnList) {
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext, array, array]
    ldc(insnList, 0);
    // stack: [capturedcontext, capturedcontext, array, array, int]
    ldc(insnList, "this");
    // stack: [capturedcontext, capturedcontext, array, array, int, string]
    ldc(insnList, Type.getObjectType(classNode.name).getInternalName());
    // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
    // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, this]
    // no need to test redaction, 'this' is never redacted
    addCapturedValueOf(insnList, limits);
    // stack: [capturedcontext, capturedcontext, array, array, int, field_value]
    insnList.add(new InsnNode(Opcodes.AASTORE));
    // stack: [capturedcontext, capturedcontext, array]
  }

  private void tryBox(Type type, InsnList insnList) {
    // expected stack top is the value to be boxed
    if (Types.isPrimitive(type)) {
      Type targetType = Types.getBoxingTargetType(type);
      invokeStatic(insnList, targetType, "valueOf", targetType, type);
    }
  }

  private void collectLocalVariables(AbstractInsnNode location, InsnList insnList) {
    // expected stack top: [capturedcontext]
    if (location == null) {
      // method capture, local variables are not initialized - bail out
      return;
    }

    if (methodNode.localVariables == null || methodNode.localVariables.isEmpty()) {
      if (Config.get().getDynamicInstrumentationInstrumentTheWorld() == null) {
        reportWarning("Missing local variable debug info");
      }
      // no local variables info - bail out
      return;
    }
    Collection<LocalVariableNode> localVarNodes;
    if (definition.isLineProbe() || hoistedLocalVars.isEmpty()) {
      localVarNodes = methodNode.localVariables;
    } else {
      localVarNodes = hoistedLocalVars;
    }
    List<LocalVariableNode> applicableVars = new ArrayList<>();
    boolean isLineProbe = definition.isLineProbe();
    for (LocalVariableNode variableNode : localVarNodes) {
      int idx = variableNode.index - localVarBaseOffset;
      if (idx >= argOffset) {
        // var is local not arg
        if (isLineProbe || hoistedLocalVars.isEmpty()) {
          if (ASMHelper.isInScope(methodNode, variableNode, location)) {
            applicableVars.add(variableNode);
          }
        } else {
          applicableVars.add(variableNode);
        }
      }
    }
    if (applicableVars.isEmpty()) {
      // no applicable local variables - bail out
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, applicableVars.size());
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    int idx = 0;
    for (LocalVariableNode variableNode : applicableVars) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, idx++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      ldc(insnList, variableNode.name);
      // stack: [capturedcontext, capturedcontext, array, array, int, name]
      Type varType = Type.getType(variableNode.desc);
      ldc(insnList, Type.getType(variableNode.desc).getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, name, type_name]
      if (Redaction.isRedactedKeyword(variableNode.name)) {
        addCapturedValueRedacted(insnList);
      } else {
        insnList.add(new VarInsnNode(varType.getOpcode(Opcodes.ILOAD), variableNode.index));
        // stack: [capturedcontext, capturedcontext, array, array, int, name, type_name, value]
        tryBox(varType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, name, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, captured_value]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addLocals",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
  }

  private void collectReturnValue(AbstractInsnNode location, InsnList insnList) {
    if (location == null) {
      // no location provided - bail out
      return;
    }
    if (location.getOpcode() != Opcodes.IRETURN
        && location.getOpcode() != Opcodes.LRETURN
        && location.getOpcode() != Opcodes.FRETURN
        && location.getOpcode() != Opcodes.DRETURN
        && location.getOpcode() != Opcodes.ARETURN) {
      // no return value - bail out
      return;
    }
    // expected stack top is [ret_value, capturedcontext]
    int captureVar = newVar(CAPTURED_CONTEXT_TYPE);
    insnList.add(new VarInsnNode(Opcodes.ASTORE, captureVar));
    // stack: [ret_value]
    Type returnType = Type.getReturnType(methodNode.desc);
    int retVar = newVar(returnType);
    if (returnType.getSize() == 2) {
      insnList.add(new InsnNode(Opcodes.DUP2));
    } else {
      insnList.add(new InsnNode(Opcodes.DUP));
    } // stack: [ret_value, ret_value]
    insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), retVar));
    // stack: [ret_value]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, captureVar));
    // stack: [ret_value, capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [ret_value,  capturedcontext, capturedcontext]
    ldc(insnList, null);
    // stack: [ret_value, capturedcontext, capturedcontext, null]
    ldc(insnList, returnType.getClassName());
    // stack: [ret_value, capturedcontext, capturedcontext, null, type_name]
    insnList.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), retVar));
    // stack: [ret_value, capturedcontext, capturedcontext, null, type_name, ret_value]
    tryBox(returnType, insnList);
    // stack: [ret_value, capturedcontext, capturedcontext, null, type_name, ret_value]
    // no name, no redaction
    addCapturedValueOf(insnList, limits);
    // stack: [ret_value, capturedcontext, capturedcontext, capturedvalue]
    invokeVirtual(insnList, CAPTURED_CONTEXT_TYPE, "addReturn", Type.VOID_TYPE, CAPTURED_VALUE);
    // stack: [ret_value, capturedcontext]
  }

  private void collectExceptionValue(AbstractInsnNode location, InsnList insnList) {
    if (location == null) {
      // bail out
      return;
    }
    // expected stack: [throwable, capturedcontext]
    int captureVar = newVar(CAPTURED_CONTEXT_TYPE);
    int throwableVar = newVar(THROWABLE_TYPE);
    insnList.add(new VarInsnNode(Opcodes.ASTORE, captureVar));
    // stack: [throwable]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [throwable, throwable]
    insnList.add(new VarInsnNode(Opcodes.ASTORE, throwableVar));
    // stack: [throwable]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, captureVar));
    // stack: [throwable, capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [throwable, capturedcontext, capturedcontext]
    insnList.add(new VarInsnNode(Opcodes.ALOAD, throwableVar));
    // stack: [throwable, capturedcontext, capturedcontext, throwable]
    invokeVirtual(insnList, CAPTURED_CONTEXT_TYPE, "addThrowable", Type.VOID_TYPE, THROWABLE_TYPE);
    // stack: [throwable, capturedcontext]
  }

  private void collectStaticFields(InsnList insnList) {
    List<FieldNode> fieldsToCapture = extractStaticFields(classNode, limits);
    if (fieldsToCapture.isEmpty()) {
      // bail out if no fields
      return;
    }
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    ldc(insnList, fieldsToCapture.size());
    // stack: [capturedcontext, capturedcontext, int]
    insnList.add(new TypeInsnNode(Opcodes.ANEWARRAY, CAPTURED_VALUE.getInternalName()));
    // stack: [capturedcontext, capturedcontext, array]
    int counter = 0;
    for (FieldNode fieldNode : fieldsToCapture) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext, array, array]
      ldc(insnList, counter++);
      // stack: [capturedcontext, capturedcontext, array, array, int]
      if (!isAccessible(fieldNode)) {
        ldc(insnList, Type.getObjectType(classNode.name));
        ldc(insnList, null);
        ldc(insnList, fieldNode.name);
        // stack: [capturedcontext, capturedcontext, array, array, int, null, string]
        invokeStatic(
            insnList,
            REFLECTIVE_FIELD_VALUE_RESOLVER_TYPE,
            "getFieldAsCapturedValue",
            CAPTURED_VALUE,
            CLASS_TYPE,
            OBJECT_TYPE,
            STRING_TYPE);
        insnList.add(new InsnNode(Opcodes.AASTORE));
        // stack: [capturedcontext, capturedcontext, array]
        continue;
      }
      ldc(insnList, fieldNode.name);
      // stack: [capturedcontext, capturedcontext, array, array, int, string]
      Type fieldType = Type.getType(fieldNode.desc);
      ldc(insnList, fieldType.getClassName());
      // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name]
      if (Redaction.isRedactedKeyword(fieldNode.name)) {
        addCapturedValueRedacted(insnList);
      } else {
        insnList.add(
            new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, fieldNode.name, fieldNode.desc));
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name,
        // field_value]
        tryBox(fieldType, insnList);
        // stack: [capturedcontext, capturedcontext, array, array, int, string, type_name, object]
        addCapturedValueOf(insnList, limits);
      }
      // stack: [capturedcontext, capturedcontext, array, array, int, captured_value]
      insnList.add(new InsnNode(Opcodes.AASTORE));
      // stack: [capturedcontext, capturedcontext, array]
    }
    invokeVirtual(
        insnList,
        CAPTURED_CONTEXT_TYPE,
        "addStaticFields",
        Type.VOID_TYPE,
        Types.asArray(CAPTURED_VALUE, 1));
    // stack: [capturedcontext]
  }

  private static boolean isAccessible(FieldNode fieldNode) {
    Object value = fieldNode.value;
    if (value instanceof Field) {
      return ((Field) value).isAccessible();
    }
    return true;
  }

  private static List<FieldNode> extractStaticFields(ClassNode classNode, Limits limits) {
    int fieldCount = 0;
    List<FieldNode> results = new ArrayList<>();
    for (FieldNode fieldNode : classNode.fields) {
      if (isStaticField(fieldNode) && !isFinalField(fieldNode)) {
        results.add(fieldNode);
        fieldCount++;
        if (fieldCount > limits.maxFieldCount) {
          return results;
        }
      }
    }
    // Collecting inherited static fields is problematic because it some cases can lead to
    // LinkageError: attempted duplicate class definition
    // as we force to load a class to get the static fields in a different order than the JVM
    // for example, when a probe is located in method overridden in enum element
    return results;
  }

  private int declareContextVar(InsnList insnList) {
    int var = newVar(CAPTURED_CONTEXT_TYPE);
    getStatic(insnList, CAPTURED_CONTEXT_TYPE, "EMPTY_CAPTURING_CONTEXT");
    insnList.add(new VarInsnNode(Opcodes.ASTORE, var));
    return var;
  }

  private int declareTimestampVar(InsnList insnList) {
    int var = newVar(LONG_TYPE);
    invokeStatic(insnList, Type.getType(System.class), "nanoTime", LONG_TYPE);
    insnList.add(new VarInsnNode(Opcodes.LSTORE, var));
    return var;
  }

  private int declareThrowableList(InsnList insnList) {
    int var = newVar(getType(ArrayList.class));
    insnList.add(new InsnNode(Opcodes.ACONST_NULL));
    insnList.add(new VarInsnNode(Opcodes.ASTORE, var));
    return var;
  }

  private void createContext(InsnList insnList, int contextVar) {
    newInstance(insnList, CAPTURED_CONTEXT_TYPE);
    // stack: [capturedcontext]
    insnList.add(new InsnNode(Opcodes.DUP));
    // stack: [capturedcontext, capturedcontext]
    invokeConstructor(insnList, CAPTURED_CONTEXT_TYPE);
    // stack: [capturedcontext]
    if (contextVar > -1) {
      insnList.add(new InsnNode(Opcodes.DUP));
      // stack: [capturedcontext, capturedcontext]
      insnList.add(new VarInsnNode(Opcodes.ASTORE, contextVar));
      // stack: [capturedcontext]
    }
  }

  private void getContext(InsnList insnList, int contextVar) {
    insnList.add(new VarInsnNode(Opcodes.ALOAD, contextVar));
  }

  private void addCapturedValueOf(InsnList insnList, Limits limits) {
    if (limits == null) {
      ldc(insnList, Limits.DEFAULT_REFERENCE_DEPTH);
      ldc(insnList, Limits.DEFAULT_COLLECTION_SIZE);
      ldc(insnList, Limits.DEFAULT_LENGTH);
      ldc(insnList, Limits.DEFAULT_FIELD_COUNT);
    } else {
      ldc(insnList, limits.getMaxReferenceDepth());
      ldc(insnList, limits.getMaxCollectionSize());
      ldc(insnList, limits.getMaxLength());
      ldc(insnList, limits.getMaxFieldCount());
    }
    // expected stack: [name, type_name, value, int, int, int, int]
    invokeStatic(
        insnList,
        CAPTURED_VALUE,
        "of",
        CAPTURED_VALUE,
        STRING_TYPE,
        STRING_TYPE,
        OBJECT_TYPE,
        INT_TYPE,
        INT_TYPE,
        INT_TYPE,
        INT_TYPE);
    // stack: [captured_value]
  }

  private void addCapturedValueRedacted(InsnList insnList) {
    // expected stack: [name, type_name]
    invokeStatic(insnList, CAPTURED_VALUE, "redacted", CAPTURED_VALUE, STRING_TYPE, STRING_TYPE);
    // stack: [captured_value]
  }
}
