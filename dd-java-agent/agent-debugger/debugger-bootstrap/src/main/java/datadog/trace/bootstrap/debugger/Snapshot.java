package datadog.trace.bootstrap.debugger;

import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
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
  private String traceId; // trace_id
  private String spanId; // span_id

  private transient boolean isCapturing = true;

  public Snapshot(java.lang.Thread thread, ProbeDetails probe) {
    this.startTs = System.nanoTime();
    this.version = VERSION;
    this.timestamp = System.currentTimeMillis();
    this.captures = new Captures();
    this.language = LANGUAGE;
    this.thread = new CapturedThread(thread);
    this.probe = probe;
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
  }

  public void setEntry(CapturedContext context) {
    if (checkCapture(context)) {
      captures.setEntry(context);
    }
  }

  public void setExit(CapturedContext context) {
    duration = System.nanoTime() - startTs;
    context.addExtension(ValueReferences.DURATION_EXTENSION_NAME, duration);
    if (checkCapture(context)) {
      captures.setReturn(context);
    }
  }

  public void addLine(CapturedContext context, int line) {
    if (checkCapture(context)) {
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

  public void commit() {
    if (!isCapturing) {
      // should we also report skipped for all additional probes?
      DebuggerContext.skipSnapshot(getProbe().getId(), DebuggerContext.SkipCause.CONDITION);
      return;
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
    DebuggerContext.addSnapshot(this);
    for (ProbeDetails additionalProbe : probe.additionalProbes) {
      DebuggerContext.addSnapshot(
          new Snapshot(
              UUID.randomUUID().toString(),
              version,
              timestamp,
              duration,
              stack,
              captures,
              new ProbeDetails(additionalProbe.id, probe.location, probe.script, probe.tags),
              language,
              thread,
              traceId,
              spanId));
    }
  }

  // /!\ Called by instrumentation /!\
  public boolean isCapturing() {
    return isCapturing;
  }

  private boolean checkCapture(CapturedContext capture) {
    DebuggerScript script = getProbe().getScript();
    if (script == null) {
      return true;
    }
    long startTs = System.nanoTime();
    try {
      if (!script.execute(capture)) {
        isCapturing = false;
      }
      return isCapturing;
    } finally {
      LOG.debug("Script evaluated in {}ns", (System.nanoTime() - startTs));
    }
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
  public static class ProbeDetails {
    public static final String ITW_PROBE_ID = "instrument-the-world-probe";
    public static final ProbeDetails UNKNOWN = new ProbeDetails("UNKNOWN", ProbeLocation.UNKNOWN);
    public static final ProbeDetails ITW_PROBE =
        new ProbeDetails(ITW_PROBE_ID, ProbeLocation.UNKNOWN);

    private final String id;
    private final ProbeLocation location;
    private final DebuggerScript script;
    private final transient List<ProbeDetails> additionalProbes;
    private final String tags;

    public ProbeDetails(String id, ProbeLocation location) {
      this(id, location, null, null, Collections.emptyList());
    }

    public ProbeDetails(String id, ProbeLocation location, DebuggerScript script, String tags) {
      this(id, location, script, tags, Collections.emptyList());
    }

    public ProbeDetails(
        String id,
        ProbeLocation location,
        DebuggerScript script,
        String tags,
        List<ProbeDetails> additionalProbes) {
      this.id = id;
      this.location = location;
      this.script = script;
      this.additionalProbes = additionalProbes;
      this.tags = tags;
    }

    public String getId() {
      return id;
    }

    public ProbeLocation getLocation() {
      return location;
    }

    public DebuggerScript getScript() {
      return script;
    }

    public String getTags() {
      return tags;
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
    private static final Pattern DOT_PATTERN = Pattern.compile("\\.");
    private final transient Map<String, Object> extensions = new HashMap<>();

    private Map<String, CapturedValue> arguments;
    private Map<String, CapturedValue> locals;
    private CapturedThrowable throwable;
    private Map<String, CapturedValue> fields;

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
    public Object resolve(String path) {
      // 'path' is a string which starts with a prefixed head element and can contain
      // a number of period separated tail elements denoting access to fields (of fields)
      String prefix = path.substring(0, 1);
      String[] parts = DOT_PATTERN.split(path.substring(1));

      String head = parts[0];
      Object target = Values.UNDEFINED_OBJECT;
      if (prefix.equals(ValueReferences.FIELD_PREFIX)) {
        target = tryRetrieveField(head);
      } else if (prefix.equals(ValueReferences.SYNTHETIC_PREFIX)) {
        target = tryRetrieveSynthetic(head);
      } else if (prefix.equals(ValueReferences.LOCALVAR_PREFIX)) {
        target = tryRetrieveLocalVar(head);
      } else if (prefix.equals(ValueReferences.ARGUMENT_PREFIX)) {
        target = tryRetrieveArgument(head);
      }
      target = followReferences(target, parts);

      return target instanceof CapturedValue ? ((CapturedValue) target).getRawValue() : target;
    }

    private Object tryRetrieveField(String name) {
      if (fields == null) {
        return Values.UNDEFINED_OBJECT;
      }
      return fields.containsKey(name) ? fields.get(name) : Values.UNDEFINED_OBJECT;
    }

    private Object tryRetrieveSynthetic(String name) {
      if (extensions == null || extensions.isEmpty()) {
        return Values.UNDEFINED_OBJECT;
      }
      return extensions.containsKey(name) ? extensions.get(name) : Values.UNDEFINED_OBJECT;
    }

    private Object tryRetrieveLocalVar(String name) {
      if (locals == null || locals.isEmpty()) {
        return Values.UNDEFINED_OBJECT;
      }
      return locals.containsKey(name) ? locals.get(name) : Values.UNDEFINED_OBJECT;
    }

    private Object tryRetrieveArgument(String name) {
      if (arguments == null || arguments.isEmpty()) {
        return Values.UNDEFINED_OBJECT;
      }
      return arguments.containsKey(name) ? arguments.get(name) : Values.UNDEFINED_OBJECT;
    }

    /**
     * Will follow the fields as described by the 'parts' argument.
     *
     * @param src the value to start the field traversing at
     * @param parts the reference path
     * @return the resolved value or {@linkplain Values#UNDEFINED_OBJECT}
     */
    private Object followReferences(Object src, String[] parts) {
      if (src == Values.UNDEFINED_OBJECT) {
        return src;
      }
      Object target = src;
      // the iteration starts with index 1 as the index 0 was used to resolve the 'src' argument
      // in order to avoid extraneous array copies the original array is passed around as is
      for (int i = 1; i < parts.length; i++) {
        if (target == Values.UNDEFINED_OBJECT) {
          break;
        }
        if (target instanceof CapturedValue) {
          Map<String, CapturedValue> fields = ((CapturedValue) target).fields;
          if (fields != null && fields.containsKey(parts[i])) {
            target = fields.get(parts[i]);
          } else {
            target = ((CapturedValue) target).getRawValue();
            if (target != null) {
              target = ReflectiveFieldValueResolver.resolve(target, target.getClass(), parts[i]);
            } else {
              target = Values.UNDEFINED_OBJECT;
            }
          }
        } else {
          target = ReflectiveFieldValueResolver.resolve(target, target.getClass(), parts[i]);
        }
      }
      return target;
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
    private final transient String name;
    private final String type;
    private final String value;
    private final transient WeakReference<Object> rawValueRef;
    private Map<String, CapturedValue> fields;
    private final String reasonNotCaptured;

    private CapturedValue(
        String name,
        String type,
        Object value,
        ValueConverter valueConverter,
        FieldExtractor.Limits fieldExtractorLimits,
        String reasonNotCaptured) {
      this.name = name;
      this.type = type;
      this.rawValueRef = new WeakReference<>(value);
      this.value = valueConverter.convert(value);
      this.fields =
          fieldExtractorLimits.maxFieldDepth >= 0
              ? FieldExtractor.extract(value, fieldExtractorLimits)
              : Collections.emptyMap();
      this.reasonNotCaptured = reasonNotCaptured;
    }

    public String getType() {
      return type;
    }

    public String getValue() {
      return value;
    }

    Object getRawValue() {
      return rawValueRef.get();
    }

    public Map<String, CapturedValue> getFields() {
      return fields;
    }

    public String getReasonNotCaptured() {
      return reasonNotCaptured;
    }

    public static Snapshot.CapturedValue of(String type, Object value) {
      return new Snapshot.CapturedValue(
          null,
          type,
          value,
          new ValueConverter(),
          new FieldExtractor.Limits(1, FieldExtractor.DEFAULT_FIELD_COUNT),
          null);
    }

    public static Snapshot.CapturedValue of(String name, String type, Object value) {
      return new Snapshot.CapturedValue(
          name,
          type,
          value,
          new ValueConverter(),
          new FieldExtractor.Limits(1, FieldExtractor.DEFAULT_FIELD_COUNT),
          null);
    }

    public static Snapshot.CapturedValue of(
        String name,
        String type,
        Object value,
        int maxReferenceDepth,
        int maxCollectionSize,
        int maxLength,
        int maxFieldDepth,
        int maxFieldCount) {
      return new Snapshot.CapturedValue(
          name,
          type,
          value,
          new ValueConverter(
              new ValueConverter.Limits(
                  maxReferenceDepth, maxCollectionSize, maxLength, maxFieldCount)),
          new FieldExtractor.Limits(maxFieldDepth, maxFieldCount),
          null);
    }

    public static Snapshot.CapturedValue reasonNotCaptured(
        String name, String type, String reason) {
      return new CapturedValue(
          name,
          type,
          null,
          new ValueConverter(),
          new FieldExtractor.Limits(1, FieldExtractor.DEFAULT_FIELD_COUNT),
          reason);
    }

    static Snapshot.CapturedValue raw(
        String type, Object value, int maxFieldDepth, int maxFieldCount) {
      return new CapturedValue(
          null,
          type,
          value,
          new ValueConverter(),
          new FieldExtractor.Limits(maxFieldDepth, maxFieldCount),
          null);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedValue that = (CapturedValue) o;
      return Objects.equals(name, that.name)
          && Objects.equals(type, that.type)
          && Objects.equals(value, that.value)
          && Objects.equals(fields, that.fields)
          && Objects.equals(reasonNotCaptured, that.reasonNotCaptured);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, type, value, fields, reasonNotCaptured);
    }

    @Override
    public String toString() {
      return "CapturedValue{"
          + "name='"
          + name
          + '\''
          + ", type='"
          + type
          + '\''
          + ", value='"
          + value
          + '\''
          + ", fields="
          + fields
          + ", reasonNotCaptured='"
          + reasonNotCaptured
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
          throwable.getClass().getName(),
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
}
