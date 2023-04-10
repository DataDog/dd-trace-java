package datadog.trace.bootstrap.debugger;

import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Data class representing all data collected at a probe location */
public class Snapshot {
  private static final Logger LOG = LoggerFactory.getLogger(Snapshot.class);
  private static final String LANGUAGE = "java";
  private static final int VERSION = 2;

  private String id;
  private final transient int version;
  private final long timestamp;
  private transient long duration;
  private final List<CapturedStackFrame> stack = new ArrayList<>();
  private final Captures captures;
  private final ProbeDetails probe;
  private final String language;
  private final transient CapturedThread thread;
  private String traceId; // trace_id
  private String spanId; // span_id
  private List<EvaluationError> evaluationErrors;
  private transient String message;

  public Snapshot(java.lang.Thread thread, ProbeDetails probeDetails) {
    this.id = UUID.randomUUID().toString();
    this.version = VERSION;
    this.timestamp = System.currentTimeMillis();
    this.captures = new Captures();
    this.language = LANGUAGE;
    this.thread = new CapturedThread(thread);
    this.probe = probeDetails;
  }

  public Snapshot(
      String id,
      int version,
      long timestamp,
      long duration,
      List<CapturedStackFrame> stack,
      Snapshot.Captures captures,
      Snapshot.ProbeDetails probeDetails,
      String language,
      Snapshot.CapturedThread thread,
      String traceId,
      String spanId) {
    this.id = id;
    this.version = version;
    this.timestamp = timestamp;
    this.duration = duration;
    this.stack.addAll(stack);
    this.captures = captures;
    this.probe = probeDetails;
    this.language = language;
    this.thread = thread;
    this.traceId = traceId;
    this.spanId = spanId;
  }

  public void setEntry(CapturedContext context) {
    captures.setEntry(context);
  }

  public void setExit(CapturedContext context) {
    captures.setReturn(context);
  }

  public void setDuration(long duration) {
    this.duration = duration;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public void addLine(CapturedContext context, int line) {
    captures.addLine(line, context);
  }

  public void addCaughtExceptions(List<CapturedThrowable> throwables) {
    if (throwables == null) {
      return;
    }
    for (CapturedThrowable throwable : throwables) {
      captures.addCaughtException(throwable);
    }
  }

  public String getId() {
    return id;
  }

  public int getVersion() {
    return version;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getDuration() {
    return duration;
  }

  public List<CapturedStackFrame> getStack() {
    return stack;
  }

  public Captures getCaptures() {
    return captures;
  }

  public ProbeDetails getProbe() {
    return probe;
  }

  public String getLanguage() {
    return language;
  }

  public CapturedThread getThread() {
    return thread;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public List<EvaluationError> getEvaluationErrors() {
    return evaluationErrors;
  }

  public void addEvaluationErrors(List<EvaluationError> errors) {
    if (errors == null || errors.isEmpty()) {
      return;
    }
    if (evaluationErrors == null) {
      evaluationErrors = new ArrayList<>();
    }
    evaluationErrors.addAll(errors);
  }

  public String getMessage() {
    return message;
  }

  public void commit() {
    /*
     * Record stack trace having the caller of this method as 'top' frame.
     * For this it is necessary to discard:
     * - Thread.currentThread().getStackTrace()
     * - Snapshot.recordStackTrace()
     * - Snapshot.commit()
     * - DebuggerContext.commit() or DebuggerContext.evalAndCommit()
     */
    recordStackTrace(4);
    DebuggerContext.addSnapshot(this);
  }

  private void recordStackTrace(int offset) {
    stack.clear();
    int cntr = 0;
    for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
      if (cntr++ < offset) {
        continue;
      }
      stack.add(CapturedStackFrame.from(ste));
    }
  }

  public enum Kind {
    ENTER,
    RETURN,
    UNHANDLED_EXCEPTION,
    HANDLED_EXCEPTION,
    BEFORE,
    AFTER;
  }

  /** Probe information associated with a snapshot */
  public interface ProbeDetails {
    String ITW_PROBE_ID = "instrument-the-world-probe";
    ProbeDetails UNKNOWN = new DummyProbe("UNKNOWN", ProbeLocation.UNKNOWN);
    ProbeDetails ITW_PROBE = new DummyProbe(ITW_PROBE_ID, ProbeLocation.UNKNOWN);

    String getId();

    ProbeLocation getLocation();

    String getStrTags();

    void evaluate(
        Snapshot.CapturedContext context,
        Snapshot.CapturedContext.Status status,
        MethodLocation methodLocation);

    MethodLocation getEvaluateAt();

    boolean isCaptureSnapshot();

    boolean hasCondition();

    class DummyProbe implements ProbeDetails {
      private final String id;
      private final int version;
      private final ProbeLocation location;
      private final MethodLocation evaluateAt;
      private final boolean captureSnapshot;
      private final DebuggerScript<Boolean> script;
      private final String tags;

      public DummyProbe(String id, ProbeLocation location) {
        this(id, 0, location, MethodLocation.DEFAULT, true, null, null);
      }

      public DummyProbe(
          String id,
          int version,
          ProbeLocation location,
          MethodLocation evaluateAt,
          boolean captureSnapshot,
          DebuggerScript<Boolean> script,
          String tags) {
        this.id = id;
        this.version = version;
        this.location = location;
        this.evaluateAt = evaluateAt;
        this.captureSnapshot = captureSnapshot;
        this.script = script;
        this.tags = tags;
      }

      @Override
      public String getId() {
        return id;
      }

      @Override
      public ProbeLocation getLocation() {
        return location;
      }

      @Override
      public String getStrTags() {
        return null;
      }

      @Override
      public void evaluate(
          CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {}

      @Override
      public MethodLocation getEvaluateAt() {
        return evaluateAt;
      }

      @Override
      public boolean isCaptureSnapshot() {
        return captureSnapshot;
      }

      @Override
      public boolean hasCondition() {
        return script != null;
      }
    }
  }

  /** Probe location information used in ProbeDetails class */
  public static class ProbeLocation {
    public static final ProbeLocation UNKNOWN =
        new ProbeLocation("UNKNOWN", "UNKNOWN", "UNKNOWN", Collections.emptyList());

    private final String type; // class
    private final String method;
    private final String file;
    private final List<String> lines;

    public ProbeLocation(String type, String method, String file, List<String> lines) {
      this.type = type;
      this.method = method;
      this.file = file;
      this.lines = lines;
    }

    public String getType() {
      return type;
    }

    public String getMethod() {
      return method;
    }

    public String getFile() {
      return file;
    }

    public List<String> getLines() {
      return lines;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ProbeLocation that = (ProbeLocation) o;
      return Objects.equals(type, that.type)
          && Objects.equals(method, that.method)
          && Objects.equals(file, that.file)
          && Objects.equals(lines, that.lines);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, method, file, lines);
    }

    @Override
    public String toString() {
      return "ProbeLocation{"
          + "type='"
          + type
          + '\''
          + ", method='"
          + method
          + '\''
          + ", file='"
          + file
          + '\''
          + ", lines="
          + lines
          + '}';
    }
  }

  /** Stores all collected data at different location (method entry/exit, lines, exceptions) */
  public static class Captures {
    private CapturedContext entry;
    private Map<Integer, CapturedContext> lines;
    // returnValue encoded into a local of CapturedContext
    private CapturedContext _return;

    private List<CapturedThrowable> caughtExceptions;

    public CapturedContext getEntry() {
      return entry;
    }

    public Map<Integer, CapturedContext> getLines() {
      return lines;
    }

    public CapturedContext getReturn() {
      return _return;
    }

    public List<CapturedThrowable> getCaughtExceptions() {
      return caughtExceptions;
    }

    public void setEntry(CapturedContext context) {
      entry = context;
    }

    public void setReturn(CapturedContext context) {
      _return = context;
    }

    public void addLine(int line, CapturedContext context) {
      if (lines == null) {
        lines = new HashMap<>();
      }
      lines.put(line, context); // /!\ boxing /!\
    }

    public void addCaughtException(CapturedThrowable context) {
      if (caughtExceptions == null) {
        caughtExceptions = new ArrayList<>();
      }
      caughtExceptions.add(context);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Captures captures = (Captures) o;
      return Objects.equals(entry, captures.entry)
          && Objects.equals(lines, captures.lines)
          && Objects.equals(_return, captures._return)
          && Objects.equals(caughtExceptions, captures.caughtExceptions);
    }

    @Override
    public int hashCode() {
      return Objects.hash(entry, lines, _return, caughtExceptions);
    }

    @Override
    public String toString() {
      return "Captures{"
          + "entry="
          + entry
          + ", lines="
          + lines
          + ", exit="
          + _return
          + ", caughtExceptions="
          + caughtExceptions
          + '}';
    }
  }

  /**
   * Stores different kind of data (arguments, locals, fields, exception) for a specific location
   */
  public static class CapturedContext implements ValueReferenceResolver {
    public static final CapturedContext EMPTY_CONTEXT =
        new CapturedContext(null, Status.EMPTY_STATUS);
    public static final CapturedContext EMPTY_CAPTURING_CONTEXT =
        new CapturedContext(ProbeDetails.UNKNOWN, Status.EMPTY_PASSING_STATUS);
    private final transient Map<String, Object> extensions = new HashMap<>();

    private Map<String, CapturedValue> arguments;
    private Map<String, CapturedValue> locals;
    private CapturedThrowable throwable;
    private Map<String, CapturedValue> fields;
    private Limits limits = Limits.DEFAULT;
    private String thisClassName;
    private List<EvaluationError> evaluationErrors;
    private String traceId;
    private String spanId;
    private long duration;
    private final Map<String, Status> statusByProbeId = new LinkedHashMap<>();
    private final Status defaultStatus;

    public CapturedContext() {
      defaultStatus = Status.EMPTY_STATUS;
    }

    public CapturedContext(
        CapturedValue[] arguments,
        CapturedValue[] locals,
        CapturedValue returnValue,
        CapturedThrowable throwable,
        CapturedValue[] fields) {
      addArguments(arguments);
      addLocals(locals);
      addReturn(returnValue);
      this.throwable = throwable;
      addFields(fields);
      defaultStatus = Status.EMPTY_STATUS;
    }

    private CapturedContext(CapturedContext other, Map<String, Object> extensions) {
      this.arguments = other.arguments;
      this.locals = other.getLocals();
      this.throwable = other.throwable;
      this.fields = other.fields;
      this.extensions.putAll(other.extensions);
      this.extensions.putAll(extensions);
      this.defaultStatus = Status.EMPTY_STATUS;
    }

    // used for EMPTY_CONTEXT
    private CapturedContext(ProbeDetails probeDetails, Status defaultStatus) {
      if (probeDetails != null) {
        this.statusByProbeId.put(probeDetails.getId(), new Status(probeDetails));
      }
      this.defaultStatus = defaultStatus;
    }

    public long getDuration() {
      return duration;
    }

    public boolean isCapturing() {
      boolean result = false;
      for (Status status : statusByProbeId.values()) {
        result |= status.condition;
      }
      return result;
    }

    @Override
    public Object lookup(String name) {
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException("empty name for lookup operation");
      }
      Object target;
      if (name.startsWith(ValueReferences.SYNTHETIC_PREFIX)) {
        String rawName = name.substring(ValueReferences.SYNTHETIC_PREFIX.length());
        target = tryRetrieveSynthetic(rawName);
        checkUndefined(name, target, rawName, "Cannot find synthetic var: ");
      } else {
        target = tryRetrieve(name);
        checkUndefined(name, target, name, "Cannot find symbol: ");
      }
      return target instanceof CapturedValue ? ((CapturedValue) target).getValue() : target;
    }

    private void checkUndefined(String expr, Object target, String name, String msg) {
      if (target == Values.UNDEFINED_OBJECT) {
        String errorMsg = msg + name;
        addEvaluationError(expr, errorMsg);
        throw new RuntimeException(errorMsg);
      }
    }

    @Override
    public Object getMember(Object target, String memberName) {
      if (target == Values.UNDEFINED_OBJECT) {
        return target;
      }
      if (target instanceof CapturedValue) {
        Map<String, CapturedValue> fields = ((CapturedValue) target).fields;
        if (fields.containsKey(memberName)) {
          target = fields.get(memberName);
        } else {
          CapturedValue capturedTarget = ((CapturedValue) target);
          target = capturedTarget.getValue();
          if (target != null) {
            // resolve to a CapturedValue instance
            target = ReflectiveFieldValueResolver.resolve(target, target.getClass(), memberName);
          } else {
            target = Values.UNDEFINED_OBJECT;
          }
        }
      } else {
        target = ReflectiveFieldValueResolver.resolve(target, target.getClass(), memberName);
      }
      checkUndefined(memberName, target, memberName, "Cannot dereference to field: ");
      return target;
    }

    private Object tryRetrieveSynthetic(String name) {
      if (extensions == null || extensions.isEmpty()) {
        return Values.UNDEFINED_OBJECT;
      }
      return extensions.getOrDefault(name, Values.UNDEFINED_OBJECT);
    }

    private Object tryRetrieve(String name) {
      Object result = null;
      if (arguments != null && !arguments.isEmpty()) {
        result = arguments.get(name);
      }
      if (result != null) {
        return result;
      }
      if (locals != null && !locals.isEmpty()) {
        result = locals.get(name);
      }
      if (result != null) {
        return result;
      }
      if (fields != null && !fields.isEmpty()) {
        result = fields.get(name);
      }
      return result != null ? result : Values.UNDEFINED_OBJECT;
    }

    @Override
    public ValueReferenceResolver withExtensions(Map<String, Object> extensions) {
      return new CapturedContext(this, extensions);
    }

    private void addExtension(String name, Object value) {
      extensions.put(name, value);
    }

    public void addArguments(CapturedValue[] values) {
      if (values == null) {
        return;
      }
      if (arguments == null) {
        arguments = new HashMap<>();
      }
      for (CapturedValue value : values) {
        arguments.put(value.name, value);
      }
    }

    public void addLocals(CapturedValue[] values) {
      if (values == null) {
        return;
      }
      if (locals == null) {
        locals = new HashMap<>();
      }
      for (CapturedValue value : values) {
        locals.put(value.name, value);
      }
    }

    public void addReturn(CapturedValue retValue) {
      if (retValue == null) {
        return;
      }
      if (locals == null) {
        locals = new HashMap<>();
      }
      locals.put("@return", retValue); // special local name for the return value
      extensions.put(ValueReferences.RETURN_EXTENSION_NAME, retValue);
    }

    public void addThrowable(Throwable t) {
      this.throwable = new CapturedThrowable(t);
    }

    public void addThrowable(CapturedThrowable capturedThrowable) {
      this.throwable = capturedThrowable;
    }

    public void addFields(CapturedValue[] values) {
      if (values == null) {
        return;
      }
      if (fields == null) {
        fields = new HashMap<>();
      }
      for (CapturedValue value : values) {
        fields.put(value.name, value);
      }
      traceId = extractSpecialId("dd.trace_id");
      spanId = extractSpecialId("dd.span_id");
    }

    private String extractSpecialId(String idName) {
      CapturedValue capturedValue = fields.get(idName);
      if (capturedValue == null) {
        return null;
      }
      Object value = capturedValue.getValue();
      return value instanceof String ? (String) value : null;
    }

    public boolean handleEvalErrors(List<Snapshot.EvaluationError> errors) {
      if (!hasEvaluationErrors()) {
        return false;
      }
      errors.addAll(evaluationErrors);
      evaluationErrors.clear();
      return true;
    }

    public void addEvaluationError(String expr, String message) {
      if (evaluationErrors == null) {
        evaluationErrors = new ArrayList<>();
      }
      evaluationErrors.add(new EvaluationError(expr, message));
    }

    boolean hasEvaluationErrors() {
      if (evaluationErrors != null) {
        return !evaluationErrors.isEmpty();
      }
      return false;
    }

    public void setLimits(
        int maxReferenceDepth, int maxCollectionSize, int maxLength, int maxFieldCount) {
      this.limits = new Limits(maxReferenceDepth, maxCollectionSize, maxLength, maxFieldCount);
    }

    public Map<String, CapturedValue> getArguments() {
      return arguments;
    }

    public Map<String, CapturedValue> getLocals() {
      return locals;
    }

    public CapturedThrowable getThrowable() {
      return throwable;
    }

    public Map<String, CapturedValue> getFields() {
      return fields;
    }

    public Limits getLimits() {
      return limits;
    }

    public String getThisClassName() {
      return thisClassName;
    }

    public String getTraceId() {
      return traceId;
    }

    public String getSpanId() {
      return spanId;
    }

    /**
     * 'Freeze' the context. The contained arguments, locals and fields are converted from their
     * Java instance representation into the corresponding string value.
     */
    public void freeze(TimeoutChecker timeoutChecker) {
      if (arguments != null) {
        arguments.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
      }
      if (locals != null) {
        locals.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
      }
      if (fields != null) {
        fields.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
      }
    }

    public Status evaluate(
        String probeId,
        ProbeDetails probeDetails,
        String thisClassName,
        long startTimestamp,
        MethodLocation methodLocation) {
      Status status = statusByProbeId.computeIfAbsent(probeId, key -> new Status(probeDetails));
      if (methodLocation == MethodLocation.EXIT) {
        duration = System.nanoTime() - startTimestamp;
        addExtension(ValueReferences.DURATION_EXTENSION_NAME, duration);
      }
      this.thisClassName = thisClassName;
      probeDetails.evaluate(this, status, methodLocation);
      return status;
    }

    public Status getStatus(String probeId) {
      Status result = statusByProbeId.get(probeId);
      if (result == null) {
        return defaultStatus;
      }
      return result;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedContext context = (CapturedContext) o;
      return Objects.equals(arguments, context.arguments)
          && Objects.equals(locals, context.locals)
          && Objects.equals(throwable, context.throwable)
          && Objects.equals(fields, context.fields);
    }

    @Override
    public int hashCode() {
      return Objects.hash(arguments, locals, throwable, fields);
    }

    @Override
    public String toString() {
      return "CapturedContext{"
          + "arguments="
          + arguments
          + ", locals="
          + locals
          + ", throwable="
          + throwable
          + ", fields="
          + fields
          + '}';
    }

    public static class Status {
      public static final Status EMPTY_STATUS = new Status(false, ProbeDetails.UNKNOWN);
      public static final Status EMPTY_PASSING_STATUS = new Status(true, ProbeDetails.UNKNOWN);
      final List<EvaluationError> errors = new ArrayList<>();
      final ProbeDetails probeDetails;
      boolean condition;
      boolean hasLogTemplateErrors;
      boolean hasConditionErrors;
      String message;

      public Status(ProbeDetails probeDetails) {
        this.condition = true;
        this.probeDetails = probeDetails;
      }

      private Status(boolean condition, ProbeDetails probeDetails) {
        this.condition = condition;
        this.probeDetails = probeDetails;
      }

      public boolean shouldSend() {
        return condition && !hasConditionErrors;
      }

      public boolean shouldReportError() {
        return hasConditionErrors || hasLogTemplateErrors;
      }

      public boolean getCondition() {
        return condition;
      }

      public void setCondition(boolean value) {
        this.condition = value;
      }

      public boolean hasConditionErrors() {
        return hasConditionErrors;
      }

      public void setConditionErrors(boolean value) {
        this.hasConditionErrors = value;
      }

      public boolean hasLogTemplateErrors() {
        return hasLogTemplateErrors;
      }

      public void setLogTemplateErrors(boolean value) {
        this.hasLogTemplateErrors = value;
      }

      public List<EvaluationError> getErrors() {
        return errors;
      }

      public void setMessage(String message) {
        this.message = message;
      }

      public String getMessage() {
        return message;
      }

      public void addError(EvaluationError evaluationError) {
        errors.add(evaluationError);
      }
    }
  }

  /** Stores a captured value */
  public static class CapturedValue {
    public static final CapturedValue UNDEFINED = CapturedValue.of(null, Values.UNDEFINED_OBJECT);

    private String name;
    private final String declaredType;
    private final String type;
    private Object value;
    private String strValue;
    private final Map<String, CapturedValue> fields;
    private final Limits limits;
    private TimeoutChecker timeoutChecker;
    private final String notCapturedReason;

    private CapturedValue(
        String name,
        String declaredType,
        Object value,
        Limits limits,
        Map<String, CapturedValue> fields,
        String notCapturedReason) {
      this.name = name;
      this.declaredType = declaredType;
      this.type =
          value != null && !isPrimitive(declaredType)
              ? value.getClass().getTypeName()
              : declaredType;
      this.value = value;
      this.fields = fields == null ? Collections.emptyMap() : fields;
      this.limits = limits;
      this.notCapturedReason = notCapturedReason;
    }

    public boolean isResolved() {
      return true;
    }

    public String getName() {
      return name;
    }

    public String getDeclaredType() {
      return declaredType;
    }

    public String getType() {
      return type;
    }

    public Object getValue() {
      return value;
    }

    public String getStrValue() {
      return strValue;
    }

    public Map<String, CapturedValue> getFields() {
      return fields;
    }

    public Limits getLimits() {
      return limits;
    }

    public String getNotCapturedReason() {
      return notCapturedReason;
    }

    public void setName(String name) {
      this.name = name;
    }

    public static Snapshot.CapturedValue of(String declaredType, Object value) {
      return build(null, declaredType, value, Limits.DEFAULT, null);
    }

    public static Snapshot.CapturedValue of(String name, String declaredType, Object value) {
      return build(name, declaredType, value, Limits.DEFAULT, null);
    }

    public Snapshot.CapturedValue derive(String name, String type, Object value) {
      return build(name, type, value, limits, null);
    }

    public static Snapshot.CapturedValue of(
        String name,
        String declaredType,
        Object value,
        int maxReferenceDepth,
        int maxCollectionSize,
        int maxLength,
        int maxFieldCount) {
      return build(
          name,
          declaredType,
          value,
          new Limits(maxReferenceDepth, maxCollectionSize, maxLength, maxFieldCount),
          null);
    }

    public static Snapshot.CapturedValue notCapturedReason(
        String name, String type, String reason) {
      return build(name, type, null, Limits.DEFAULT, reason);
    }

    public static Snapshot.CapturedValue raw(String type, Object value, String notCapturedReason) {
      return new CapturedValue(
          null, type, value, Limits.DEFAULT, Collections.emptyMap(), notCapturedReason);
    }

    public static Snapshot.CapturedValue raw(
        String name,
        String type,
        Object value,
        Limits limits,
        Map<String, CapturedValue> fields,
        String notCapturedReason) {
      return new CapturedValue(name, type, value, limits, fields, notCapturedReason);
    }

    private static CapturedValue build(
        String name, String declaredType, Object value, Limits limits, String notCapturedReason) {
      CapturedValue val =
          new CapturedValue(
              name, declaredType, value, limits, Collections.emptyMap(), notCapturedReason);
      return val;
    }

    public void freeze(TimeoutChecker timeoutChecker) {
      if (this.strValue != null) {
        // already frozen
        return;
      }
      this.timeoutChecker = timeoutChecker;
      this.strValue = DebuggerContext.serializeValue(this);
      if (this.strValue != null) {
        // if serialization has happened, release the value object
        this.value = null;
      }
    }

    private static boolean isPrimitive(String type) {
      if (type == null) {
        return false;
      }
      switch (type) {
        case "byte":
        case "short":
        case "char":
        case "int":
        case "long":
        case "boolean":
        case "float":
        case "double":
          return true;
      }
      return false;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedValue that = (CapturedValue) o;
      return Objects.equals(name, that.name)
          && Objects.equals(declaredType, that.declaredType)
          && Objects.equals(value, that.value)
          && Objects.equals(fields, that.fields)
          && Objects.equals(notCapturedReason, that.notCapturedReason);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, declaredType, value, fields, notCapturedReason);
    }

    @Override
    public String toString() {
      return "CapturedValue{"
          + "name='"
          + name
          + '\''
          + ", type='"
          + declaredType
          + '\''
          + ", value='"
          + value
          + '\''
          + ", fields="
          + fields
          + ", notCapturedReason='"
          + notCapturedReason
          + '\''
          + '}';
    }

    public TimeoutChecker getTimeoutChecker() {
      return timeoutChecker;
    }
  }

  public static class CapturedThread {
    private final long id;
    private final String name;

    public CapturedThread(java.lang.Thread thread) {
      this(thread.getId(), thread.getName());
    }

    public CapturedThread(long id, String name) {
      this.id = id;
      this.name = name;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedThread that = (CapturedThread) o;
      return id == that.id && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, name);
    }

    @Override
    public String toString() {
      return "CapturedThread{" + "id=" + id + ", name='" + name + '\'' + '}';
    }
  }

  /** Stores an captured exception */
  public static class CapturedThrowable {
    private final String type;
    private final String message;

    /*
     * Need to exclude stacktrace from equals/hashCode computation.
     * It is making equal-based testing very difficult and in fact it is not really necessary.
     */
    private final List<CapturedStackFrame> stacktrace;

    public CapturedThrowable(Throwable throwable) {
      this(
          throwable.getClass().getTypeName(),
          throwable.getLocalizedMessage(),
          captureFrames(throwable.getStackTrace()));
    }

    public CapturedThrowable(String type, String message, List<CapturedStackFrame> stacktrace) {
      this.type = type;
      this.message = message;
      this.stacktrace = new ArrayList<>(stacktrace);
    }

    public String getType() {
      return type;
    }

    public String getMessage() {
      return message;
    }

    public List<CapturedStackFrame> getStacktrace() {
      return stacktrace;
    }

    private static List<CapturedStackFrame> captureFrames(StackTraceElement[] stackTrace) {
      if (stackTrace == null) {
        return Collections.emptyList();
      }
      List<CapturedStackFrame> capturedFrames = new ArrayList<>(stackTrace.length);
      for (StackTraceElement element : stackTrace) {
        capturedFrames.add(CapturedStackFrame.from(element));
      }
      return capturedFrames;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedThrowable that = (CapturedThrowable) o;
      return Objects.equals(type, that.type)
          && Objects.equals(message, that.message)
          && Objects.equals(stacktrace, that.stacktrace);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, message, stacktrace);
    }

    @Override
    public String toString() {
      return "CapturedThrowable{"
          + "type='"
          + type
          + '\''
          + ", message='"
          + message
          + '\''
          + ", stacktrace="
          + stacktrace
          + '}';
    }
  }

  /**
   * Store evaluation errors from expressions (probe conditions, log template, metric values, ...)
   */
  public static class EvaluationError {
    private final String expr;
    private final String message;

    public EvaluationError(String expr, String message) {
      this.expr = expr;
      this.message = message;
    }

    public String getExpr() {
      return expr;
    }

    public String getMessage() {
      return message;
    }
  }

  private static class SnapshotStatus {
    boolean capturing;
    boolean sending;
    boolean hasConditionErrors;
    boolean hasLogTemplateErrors;
    ProbeDetails probeDetails;

    public SnapshotStatus(boolean capturing, boolean sending, ProbeDetails probeDetails) {
      this.capturing = capturing;
      this.sending = sending;
      this.probeDetails = probeDetails;
    }

    public boolean isCapturing() {
      return capturing;
    }

    public void setHasConditionErrors() {
      this.hasConditionErrors = true;
    }

    public void setHasLogTemplateErrors() {
      this.hasLogTemplateErrors = true;
    }

    @Override
    public String toString() {
      return "SnapshotStatus{"
          + "capturing="
          + capturing
          + ", sending="
          + sending
          + ", hasConditionErrors="
          + hasConditionErrors
          + ", hasLogTemplateErrors="
          + hasLogTemplateErrors
          + ", probeDetails="
          + probeDetails
          + '}';
    }
  }
}
