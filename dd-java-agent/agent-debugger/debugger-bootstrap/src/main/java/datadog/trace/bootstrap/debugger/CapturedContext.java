package datadog.trace.bootstrap.debugger;

import static datadog.trace.bootstrap.debugger.util.Redaction.REDACTED_VALUE;

import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Stores different kind of data (arguments, locals, fields, exception) for a specific location */
public class CapturedContext implements ValueReferenceResolver {
  public static final CapturedContext EMPTY_CONTEXT = new CapturedContext(null);
  public static final CapturedContext EMPTY_CAPTURING_CONTEXT =
      new CapturedContext(ProbeImplementation.UNKNOWN);
  private final transient Map<String, Object> extensions = new HashMap<>();

  private Map<String, CapturedValue> arguments;
  private Map<String, CapturedValue> locals;
  private CapturedThrowable throwable;
  private Map<String, CapturedValue> staticFields;
  private Limits limits = Limits.DEFAULT;
  private String thisClassName;
  private long duration;
  private final Map<String, Status> statusByProbeId = new LinkedHashMap<>();
  private Map<String, CapturedValue> captureExpressions;

  public CapturedContext() {}

  public CapturedContext(
      CapturedValue[] arguments,
      CapturedValue[] locals,
      CapturedValue returnValue,
      CapturedThrowable throwable) {
    addArguments(arguments);
    addLocals(locals);
    addReturn(returnValue);
    this.throwable = throwable;
  }

  private CapturedContext(CapturedContext other, Map<String, Object> extensions) {
    this.arguments = other.arguments;
    this.locals = other.getLocals();
    this.throwable = other.throwable;
    this.extensions.putAll(other.extensions);
    this.extensions.putAll(extensions);
  }

  // used for EMPTY_CONTEXT
  private CapturedContext(ProbeImplementation probeImplementation) {
    if (probeImplementation != null) {
      this.statusByProbeId.put(
          probeImplementation.getProbeId().getEncodedId(), probeImplementation.createStatus());
    }
  }

  public long getDuration() {
    return duration;
  }

  // Called by CapturedContext instrumentation
  public boolean isCapturing() {
    boolean result = false;
    for (Status status : statusByProbeId.values()) {
      result |= status.isCapturing();
    }
    result = result && DebuggerContext.checkAndSetInProbe();
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
      checkUndefined(target, rawName, "Cannot find synthetic var: ");
    } else {
      target = tryRetrieve(name);
      checkUndefined(target, name, "Cannot find symbol: ");
    }
    return target instanceof CapturedValue ? ((CapturedValue) target).getValue() : target;
  }

  private void checkUndefined(Object target, String name, String msg) {
    if (target == Values.UNDEFINED_OBJECT) {
      String errorMsg = msg + name;
      throw new RuntimeException(errorMsg);
    }
  }

  @Override
  public Object getMember(Object target, String memberName) {
    if (target == Values.UNDEFINED_OBJECT) {
      return target;
    }
    if (Redaction.isRedactedKeyword(memberName)) {
      return REDACTED_VALUE;
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
      Map<String, Function<Object, CapturedValue>> specialTypeAccess =
          WellKnownClasses.getSpecialTypeAccess(target);
      if (specialTypeAccess != null) {
        Function<Object, CapturedValue> specialFieldAccess = specialTypeAccess.get(memberName);
        if (specialFieldAccess != null) {
          CapturedValue specialField = specialFieldAccess.apply(target);
          if (specialField != null && specialField.getName().equals(memberName)) {
            return specialField.getValue();
          }
        }
      }
      target = ReflectiveFieldValueResolver.resolve(target, target.getClass(), memberName);
    }
    checkUndefined(target, memberName, "Cannot dereference field: ");
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
    if (staticFields != null && !staticFields.isEmpty()) {
      result = staticFields.get(name);
    }
    CapturedValue thisValue;
    if (arguments != null && (thisValue = arguments.get("this")) != null) {
      result = getMember(thisValue.getValue(), name);
      if (result != Values.UNDEFINED_OBJECT) {
        return result;
      }
    }
    return result != null ? result : Values.UNDEFINED_OBJECT;
  }

  @Override
  public ValueReferenceResolver withExtensions(Map<String, Object> extensions) {
    return new CapturedContext(this, extensions);
  }

  @Override
  public void addExtension(String name, Object value) {
    extensions.put(name, value);
  }

  @Override
  public void removeExtension(String name) {
    extensions.remove(name);
  }

  public void addArguments(CapturedValue[] values) {
    if (values == null) {
      return;
    }
    for (CapturedValue value : values) {
      putInArguments(value.name, value);
    }
  }

  public void addLocals(CapturedValue[] values) {
    if (values == null) {
      return;
    }
    for (CapturedValue value : values) {
      putInLocals(value.name, value);
    }
  }

  public void addReturn(CapturedValue retValue) {
    if (retValue == null) {
      return;
    }
    // special local name for the return value
    putInLocals(ValueReferences.RETURN_REF, retValue);
    extensions.put(ValueReferences.RETURN_EXTENSION_NAME, retValue);
  }

  public void addThrowable(Throwable t) {
    addThrowable(new CapturedThrowable(t));
    // special local name for throwable
    putInLocals(ValueReferences.EXCEPTION_REF, CapturedValue.of(t.getClass().getTypeName(), t));
    extensions.put(ValueReferences.EXCEPTION_EXTENSION_NAME, t);
  }

  public void addThrowable(CapturedThrowable capturedThrowable) {
    this.throwable = capturedThrowable;
  }

  public void addStaticFields(CapturedValue[] values) {
    if (values == null) {
      return;
    }
    for (CapturedValue value : values) {
      putInStaticFields(value.name, value);
    }
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

  public CapturedThrowable getCapturedThrowable() {
    return throwable;
  }

  public Map<String, CapturedValue> getStaticFields() {
    return staticFields;
  }

  public Map<String, CapturedValue> getCaptureExpressions() {
    return captureExpressions;
  }

  public Limits getLimits() {
    return limits;
  }

  public String getThisClassName() {
    return thisClassName;
  }

  /**
   * 'Freeze' the context. The contained arguments, locals and fields are converted from their Java
   * instance representation into the corresponding string value.
   */
  public void freeze(TimeoutChecker timeoutChecker) {
    if (captureExpressions != null) {
      // freeze only capture expressions
      captureExpressions.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
      return;
    }
    if (arguments != null) {
      arguments.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
    }
    if (locals != null) {
      locals.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
    }
    if (staticFields != null) {
      staticFields.values().forEach(capturedValue -> capturedValue.freeze(timeoutChecker));
    }
  }

  public Status evaluate(
      String encodedProbeId,
      ProbeImplementation probeImplementation,
      String thisClassName,
      long startTimestamp,
      MethodLocation methodLocation) {
    Status status =
        statusByProbeId.computeIfAbsent(encodedProbeId, key -> probeImplementation.createStatus());
    if (methodLocation == MethodLocation.EXIT && startTimestamp > 0) {
      duration = System.nanoTime() - startTimestamp;
      addExtension(
          ValueReferences.DURATION_EXTENSION_NAME, duration / 1_000_000.0); // convert to ms
    }
    this.thisClassName = thisClassName;
    boolean shouldEvaluate =
        MethodLocation.isSame(methodLocation, probeImplementation.getEvaluateAt());
    if (shouldEvaluate) {
      probeImplementation.evaluate(this, status, methodLocation);
    }
    return status;
  }

  public Status getStatus(String encodedProbeId) {
    Status result = statusByProbeId.get(encodedProbeId);
    if (result == null) {
      result = statusByProbeId.get(ProbeImplementation.UNKNOWN.getProbeId().getEncodedId());
      if (result == null) {
        return Status.EMPTY_STATUS;
      }
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
        && Objects.equals(staticFields, context.staticFields)
        && Objects.equals(throwable, context.throwable);
  }

  @Override
  public int hashCode() {
    return Objects.hash(arguments, locals, throwable, staticFields);
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
        + ", staticFields="
        + staticFields
        + '}';
  }

  private void putInLocals(String name, CapturedValue value) {
    if (locals == null) {
      locals = new HashMap<>();
    }
    locals.put(name, value);
  }

  private void putInArguments(String name, CapturedValue value) {
    if (arguments == null) {
      arguments = new HashMap<>();
    }
    arguments.put(name, value);
  }

  private void putInStaticFields(String name, CapturedValue value) {
    if (staticFields == null) {
      staticFields = new HashMap<>();
    }
    staticFields.put(name, value);
  }

  public void addCaptureExpression(CapturedValue value) {
    if (captureExpressions == null) {
      captureExpressions = new HashMap<>();
    }
    captureExpressions.put(value.name, value);
  }

  public static class Status {
    public static final Status EMPTY_STATUS = new Status(ProbeImplementation.UNKNOWN);
    public static final Status EMPTY_CAPTURING_STATUS =
        new Status(ProbeImplementation.UNKNOWN) {
          @Override
          public boolean isCapturing() {
            return true;
          }
        };
    private final List<EvaluationError> errors = new ArrayList<>();
    protected final ProbeImplementation probeImplementation;

    public Status(ProbeImplementation probeImplementation) {
      this.probeImplementation = probeImplementation;
    }

    public List<EvaluationError> getErrors() {
      return errors;
    }

    public void addError(EvaluationError evaluationError) {
      errors.add(evaluationError);
    }

    public boolean shouldFreezeContext() {
      return false;
    }

    public boolean isCapturing() {
      return false;
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

    public static CapturedValue of(String declaredType, Object value) {
      return build(null, declaredType, value, Limits.DEFAULT, null);
    }

    public static CapturedValue of(String name, String declaredType, Object value) {
      return build(name, declaredType, value, Limits.DEFAULT, null);
    }

    public CapturedValue derive(String name, String type, Object value) {
      return build(name, type, value, limits, null);
    }

    public static CapturedValue of(
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

    public static CapturedValue redacted(String name, String type) {
      return build(name, type, REDACTED_VALUE, Limits.DEFAULT, null);
    }

    public static CapturedValue notCapturedReason(String name, String type, String reason) {
      return build(name, type, null, Limits.DEFAULT, reason);
    }

    public static CapturedValue raw(String type, Object value, String notCapturedReason) {
      return new CapturedValue(
          null, type, value, Limits.DEFAULT, Collections.emptyMap(), notCapturedReason);
    }

    public static CapturedValue raw(
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

  /** Stores an captured exception */
  public static class CapturedThrowable {
    private final String type;
    private final String message;
    private final transient WeakReference<Throwable> throwable;

    /*
     * Need to exclude stacktrace from equals/hashCode computation.
     * It is making equal-based testing very difficult and in fact it is not really necessary.
     */
    private final List<CapturedStackFrame> stacktrace;

    public CapturedThrowable(Throwable throwable) {
      this(
          throwable.getClass().getTypeName(),
          throwable.getLocalizedMessage(),
          captureFrames(throwable.getStackTrace()),
          throwable);
    }

    public CapturedThrowable(
        String type, String message, List<CapturedStackFrame> stacktrace, Throwable t) {
      this.type = type;
      this.message = message;
      this.stacktrace = new ArrayList<>(stacktrace);
      this.throwable = new WeakReference<>(t);
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

    public Throwable getThrowable() {
      return throwable.get();
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
}
