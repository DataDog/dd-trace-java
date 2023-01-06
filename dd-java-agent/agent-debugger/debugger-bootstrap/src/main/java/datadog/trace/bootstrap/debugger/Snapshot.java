package datadog.trace.bootstrap.debugger;

import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Data class representing all data collected at a probe location */
public class Snapshot {
  private static final Logger LOG = LoggerFactory.getLogger(Snapshot.class);
  private static final String LANGUAGE = "java";
  private static final int VERSION = 2;

  private String id;
  private final transient long startTs;
  private final transient int version;
  private final long timestamp;
  private transient long duration;
  private final List<CapturedStackFrame> stack = new ArrayList<>();
  private final Captures captures;
  private final ProbeDetails probe;
  private final String language;
  private final transient CapturedThread thread;
  private final transient Set<String> capturingProbeIds = new HashSet<>();
  private final transient Map<String, List<EvaluationError>> errorsByProbeIds = new HashMap<>();
  private final transient String thisClassName;
  private String traceId; // trace_id
  private String spanId; // span_id
  private List<EvaluationError> evaluationErrors;
  private final transient SummaryBuilder summaryBuilder;

  public Snapshot(java.lang.Thread thread, ProbeDetails probeDetails, String thisClassName) {
    this.startTs = System.nanoTime();
    this.version = VERSION;
    this.timestamp = System.currentTimeMillis();
    this.captures = new Captures();
    this.language = LANGUAGE;
    this.thread = new CapturedThread(thread);
    this.probe = probeDetails;
    this.thisClassName = thisClassName;
    this.summaryBuilder = probeDetails.summaryBuilder;
    addCapturingProbeId(probeDetails);
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
      String thisClassName,
      String traceId,
      String spanId) {
    this.startTs = System.nanoTime();
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
    this.thisClassName = thisClassName;
    this.summaryBuilder = probeDetails.summaryBuilder;
    addCapturingProbeId(this.probe);
  }

  private void addCapturingProbeId(ProbeDetails probe) {
    if (probe != null) {
      capturingProbeIds.add(probe.id);
    }
  }

  public void setEntry(CapturedContext context) {
    summaryBuilder.addEntry(context);
    context.setThisClassName(thisClassName);
    if (checkCapture(context, MethodLocation.ENTRY)) {
      captures.setEntry(context);
    }
  }

  public void setExit(CapturedContext context) {
    duration = System.nanoTime() - startTs;
    context.addExtension(ValueReferences.DURATION_EXTENSION_NAME, duration);
    summaryBuilder.addExit(context);
    context.setThisClassName(thisClassName);
    if (checkCapture(context, MethodLocation.EXIT)) {
      captures.setReturn(context);
    }
  }

  public void addLine(CapturedContext context, int line) {
    summaryBuilder.addLine(context);
    context.setThisClassName(thisClassName);
    if (checkCapture(context, MethodLocation.DEFAULT)) {
      captures.addLine(line, context);
    }
  }

  public void addCaughtException(CapturedContext context) {
    captures.addCaughtException(context.throwable);
  }

  public String getId() {
    return id;
  }

  // not using getVersion naming to avoid serialization without using annotation and pulling
  // Jackson dependency for this module
  public int retrieveVersion() {
    return version;
  }

  public long getTimestamp() {
    return timestamp;
  }

  // not using getDuration naming to avoid serialization without using annotation and pulling
  // Jackson dependency for this module
  public long retrieveDuration() {
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

  // not using getThread naming to avoid serialization without using annotation and pulling Jackson
  // dependency for this module
  public CapturedThread retrieveThread() {
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

  public String getSummary() {
    return summaryBuilder.build();
  }

  public void commit() {
    if (!isCapturing() && errorsByProbeIds.isEmpty()) {
      DebuggerContext.skipSnapshot(probe.id, DebuggerContext.SkipCause.CONDITION);
      for (ProbeDetails probeDetails : probe.additionalProbes) {
        DebuggerContext.skipSnapshot(probeDetails.id, DebuggerContext.SkipCause.CONDITION);
      }
      return;
    }
    // only rate limit if a condition is defined
    if (probe.getScript() != null && probe.isSnapshotProbe()) {
      if (!ProbeRateLimiter.tryProbe(probe.id)) {
        DebuggerContext.skipSnapshot(probe.id, DebuggerContext.SkipCause.RATE);
        return;
      }
    }
    // generates id only when effectively committing
    this.id = UUID.randomUUID().toString();
    /*
     * Record stack trace having the caller of this method as 'top' frame.
     * For this it is necessary to discard:
     * - Thread.currentThread().getStackTrace()
     * - Snapshot.recordStackTrace()
     * - Snapshot.commit()
     */
    recordStackTrace(3);
    if (capturingProbeIds.contains(probe.id) || errorsByProbeIds.containsKey(probe.id)) {
      DebuggerContext.addSnapshot(this);
    } else {
      DebuggerContext.skipSnapshot(probe.id, DebuggerContext.SkipCause.CONDITION);
    }
    for (ProbeDetails additionalProbe : probe.additionalProbes) {
      if (capturingProbeIds.contains(additionalProbe.id)
          || errorsByProbeIds.containsKey(additionalProbe.id)) {
        DebuggerContext.addSnapshot(copy(additionalProbe.id, UUID.randomUUID().toString()));
      } else {
        DebuggerContext.skipSnapshot(additionalProbe.id, DebuggerContext.SkipCause.CONDITION);
      }
    }
  }

  private Snapshot copy(String probeId, String newSnapshotId) {
    Snapshot snapshot =
        new Snapshot(
            newSnapshotId,
            version,
            timestamp,
            duration,
            stack,
            captures,
            new ProbeDetails(
                probeId,
                probe.location,
                probe.evaluateAt,
                probe.script,
                probe.tags,
                summaryBuilder),
            language,
            thread,
            thisClassName,
            traceId,
            spanId);
    List<EvaluationError> evalErrors = errorsByProbeIds.get(probeId);
    if (evalErrors != null) {
      snapshot.evaluationErrors = new ArrayList<>(evalErrors);
    }
    return snapshot;
  }

  // /!\ Called by instrumentation /!\
  public boolean isCapturing() {
    return !capturingProbeIds.isEmpty();
  }

  private boolean checkCapture(CapturedContext capture, MethodLocation methodLocation) {
    if (evaluateConditions(probe, methodLocation)) {
      DebuggerScript script = probe.getScript();
      if (!executeScript(script, capture, probe.id)) {
        capturingProbeIds.remove(probe.id);
      }
      if (capture.hasEvaluationErrors()) {
        evaluationErrors = extractEvaluationErrors(capture);
        errorsByProbeIds.put(probe.id, evaluationErrors);
      }
    }
    List<ProbeDetails> additionalProbes = probe.additionalProbes;
    if (!additionalProbes.isEmpty()) {
      for (ProbeDetails additionalProbe : additionalProbes) {
        if (evaluateConditions(additionalProbe, methodLocation)
            && executeScript(additionalProbe.getScript(), capture, additionalProbe.id)) {
          capturingProbeIds.add(additionalProbe.id);
        } else if (capture.hasEvaluationErrors()) {
          errorsByProbeIds.put(additionalProbe.id, extractEvaluationErrors(capture));
        }
      }
    }
    boolean ret = isCapturing();
    if (ret) {
      capture.freeze();
    }
    return ret;
  }

  private boolean evaluateConditions(ProbeDetails probe, MethodLocation methodLocation) {
    if (methodLocation == MethodLocation.DEFAULT || methodLocation == MethodLocation.ENTRY) {
      return probe.getEvaluateAt() == MethodLocation.DEFAULT
          || probe.getEvaluateAt() == MethodLocation.ENTRY;
    }
    return probe.getEvaluateAt() == methodLocation;
  }

  private List<EvaluationError> extractEvaluationErrors(CapturedContext capture) {
    List<EvaluationError> evalErrors = new ArrayList<>();
    evalErrors.addAll(capture.evaluationErrors);
    capture.evaluationErrors.clear();
    return evalErrors;
  }

  private static boolean executeScript(
      DebuggerScript script, CapturedContext capture, String probeId) {
    if (script == null) {
      return true;
    }
    long startTs = System.nanoTime();
    try {
      if (!script.execute(capture)) {
        return false;
      }
    } finally {
      LOG.debug("Script for probe[{}] evaluated in {}ns", probeId, (System.nanoTime() - startTs));
    }
    return true;
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
    summaryBuilder.addStack(stack);
  }

  public enum Kind {
    ENTER,
    RETURN,
    UNHANDLED_EXCEPTION,
    HANDLED_EXCEPTION,
    BEFORE,
    AFTER;
  }

  public enum MethodLocation {
    DEFAULT,
    ENTRY,
    EXIT
  }

  /** Probe information associated with a snapshot */
  public static class ProbeDetails {
    public static final String ITW_PROBE_ID = "instrument-the-world-probe";
    public static final ProbeDetails UNKNOWN = new ProbeDetails("UNKNOWN", ProbeLocation.UNKNOWN);
    public static final ProbeDetails ITW_PROBE =
        new ProbeDetails(ITW_PROBE_ID, ProbeLocation.UNKNOWN);

    private final String id;
    private final ProbeLocation location;
    private final MethodLocation evaluateAt;
    private final DebuggerScript script;
    private final transient List<ProbeDetails> additionalProbes;
    private final String tags;
    private final transient SummaryBuilder summaryBuilder;

    public ProbeDetails(String id, ProbeLocation location) {
      this(
          id,
          location,
          MethodLocation.DEFAULT,
          null,
          null,
          new SnapshotSummaryBuilder(location),
          Collections.emptyList());
    }

    public ProbeDetails(
        String id,
        ProbeLocation location,
        MethodLocation evaluateAt,
        DebuggerScript script,
        String tags,
        SummaryBuilder summaryBuilder) {
      this(id, location, evaluateAt, script, tags, summaryBuilder, Collections.emptyList());
    }

    public ProbeDetails(
        String id,
        ProbeLocation location,
        MethodLocation evaluateAt,
        DebuggerScript script,
        String tags,
        SummaryBuilder summaryBuilder,
        List<ProbeDetails> additionalProbes) {
      this.id = id;
      this.location = location;
      this.evaluateAt = evaluateAt;
      this.script = script;
      this.additionalProbes = additionalProbes;
      this.tags = tags;
      this.summaryBuilder = summaryBuilder;
    }

    public String getId() {
      return id;
    }

    public ProbeLocation getLocation() {
      return location;
    }

    public MethodLocation getEvaluateAt() {
      return evaluateAt;
    }

    public DebuggerScript getScript() {
      return script;
    }

    public String getTags() {
      return tags;
    }

    public boolean isSnapshotProbe() {
      return summaryBuilder instanceof SnapshotSummaryBuilder;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ProbeDetails that = (ProbeDetails) o;
      return Objects.equals(id, that.id)
          && Objects.equals(location, that.location)
          && Objects.equals(script, that.script)
          && Objects.equals(tags, that.tags);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, location, script, tags);
    }

    @Override
    public String toString() {
      return "ProbeDetails{"
          + "id='"
          + id
          + '\''
          + ", probeLocation="
          + location
          + ", script="
          + script
          + ", tags="
          + tags
          + '}';
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
    private final transient Map<String, Object> extensions = new HashMap<>();

    private Map<String, CapturedValue> arguments;
    private Map<String, CapturedValue> locals;
    private CapturedThrowable throwable;
    private Map<String, CapturedValue> fields;
    private Limits limits = Limits.DEFAULT;
    private String thisClassName;
    private List<EvaluationError> evaluationErrors;

    public CapturedContext() {}

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
    }

    private CapturedContext(CapturedContext other, Map<String, Object> extensions) {
      this.arguments = other.arguments;
      this.locals = other.getLocals();
      this.throwable = other.throwable;
      this.fields = other.fields;
      this.extensions.putAll(other.extensions);
      this.extensions.putAll(extensions);
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
      } else if (name.startsWith(ValueReferences.FIELD_PREFIX)) {
        String rawName = name.substring(ValueReferences.FIELD_PREFIX.length());
        target = tryRetrieveField(rawName);
        checkUndefined(name, target, rawName, "Cannot find field: ");
      } else {
        target = tryRetrieve(name);
        checkUndefined(name, target, name, "Cannot find symbol: ");
      }
      return target instanceof CapturedValue ? ((CapturedValue) target).getValue() : target;
    }

    private void checkUndefined(String expr, Object target, String name, String msg) {
      if (target == Values.UNDEFINED_OBJECT) {
        addEvalError(expr, msg + name);
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

    private Object tryRetrieveField(String name) {
      if (fields == null) {
        return Values.UNDEFINED_OBJECT;
      }
      Object field = fields.get(name);
      return field != null ? field : Values.UNDEFINED_OBJECT;
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
    }

    public void addEvalError(String expr, String message) {
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

    public void setThisClassName(String thisClassName) {
      this.thisClassName = thisClassName;
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

    /**
     * 'Freeze' the context. The contained arguments, locals and fields are converted from their
     * Java instance representation into the corresponding string value.
     */
    public void freeze() {
      if (arguments != null) {
        arguments.values().forEach(CapturedValue::freeze);
      }
      if (locals != null) {
        locals.values().forEach(CapturedValue::freeze);
      }
      if (fields != null) {
        fields.values().forEach(CapturedValue::freeze);
      }
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

    public void freeze() {
      if (this.strValue != null) {
        // already frozen
        return;
      }
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
}
