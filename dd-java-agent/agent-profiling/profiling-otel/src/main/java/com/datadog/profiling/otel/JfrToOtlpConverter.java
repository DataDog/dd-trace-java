package com.datadog.profiling.otel;

import com.datadog.profiling.otel.jfr.ExecutionSample;
import com.datadog.profiling.otel.jfr.JavaMonitorEnter;
import com.datadog.profiling.otel.jfr.JavaMonitorWait;
import com.datadog.profiling.otel.jfr.JfrClass;
import com.datadog.profiling.otel.jfr.JfrMethod;
import com.datadog.profiling.otel.jfr.JfrStackFrame;
import com.datadog.profiling.otel.jfr.JfrStackTrace;
import com.datadog.profiling.otel.jfr.MethodSample;
import com.datadog.profiling.otel.jfr.ObjectSample;
import com.datadog.profiling.otel.proto.OtlpProtoFields;
import com.datadog.profiling.otel.proto.ProtobufEncoder;
import com.datadog.profiling.otel.proto.dictionary.AttributeTable;
import com.datadog.profiling.otel.proto.dictionary.FunctionTable;
import com.datadog.profiling.otel.proto.dictionary.LinkTable;
import com.datadog.profiling.otel.proto.dictionary.LocationTable;
import com.datadog.profiling.otel.proto.dictionary.StackTable;
import com.datadog.profiling.otel.proto.dictionary.StringTable;
import datadog.json.JsonWriter;
import datadog.trace.api.profiling.RecordingData;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.TypedJafarParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Converts JFR recordings to OTLP profiles format.
 *
 * <p>This converter uses a builder-like pattern: add one or more JFR files, then call {@link
 * #convert()} to produce the OTLP protobuf output. Multiple files are merged into a single OTLP
 * ProfilesData message with shared dictionary tables for better compression.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * JfrToOtlpConverter converter = new JfrToOtlpConverter();
 * byte[] result = converter
 *     .addRecording(recording1)
 *     .addRecording(recording2)
 *     .convert();
 * }</pre>
 *
 * <p>The converter can be reused after calling {@link #convert()} - it automatically resets state.
 */
public final class JfrToOtlpConverter {

  /** Output format for profile conversion. */
  public enum Kind {
    /** Protobuf binary format (default). */
    PROTO,
    /** JSON text format. */
    JSON
  }

  private static final class PathEntry {
    final Path path;
    final boolean ephemeral;

    PathEntry(Path path, boolean ephemeral) {
      this.path = path;
      this.ephemeral = ephemeral;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;
      PathEntry pathEntry = (PathEntry) o;
      return Objects.equals(path, pathEntry.path);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(path);
    }
  }

  // Profile type names
  private static final String PROFILE_TYPE_CPU = "cpu";
  private static final String PROFILE_TYPE_WALL = "wall";
  private static final String PROFILE_TYPE_ALLOC = "alloc-samples";
  private static final String PROFILE_TYPE_LOCK = "lock-contention";

  // Units
  private static final String UNIT_SAMPLES = "samples";
  private static final String UNIT_BYTES = "bytes";
  private static final String UNIT_NANOSECONDS = "nanoseconds";

  // Dictionary tables (shared across all samples)
  private final StringTable stringTable = new StringTable();
  private final FunctionTable functionTable = new FunctionTable();
  private final LocationTable locationTable = new LocationTable();
  private final StackTable stackTable = new StackTable();
  private final LinkTable linkTable = new LinkTable();
  private final AttributeTable attributeTable = new AttributeTable();

  // Stack trace cache: maps (stackTraceId + chunkId) → stack index
  // This avoids redundant frame processing for duplicate stack traces
  private final java.util.Map<Long, Integer> stackTraceCache = new java.util.HashMap<>();

  // Sample collectors by profile type
  private final List<SampleData> cpuSamples = new ArrayList<>();
  private final List<SampleData> wallSamples = new ArrayList<>();
  private final List<SampleData> allocSamples = new ArrayList<>();
  private final List<SampleData> lockSamples = new ArrayList<>();

  private final Set<PathEntry> pathEntries = new HashSet<>();

  // Profile metadata
  private long startTimeNanos;
  private long endTimeNanos;

  /** Holds data for a single sample before encoding. */
  private static final class SampleData {
    final int stackIndex;
    final int linkIndex;
    final long value;
    final long timestampNanos;

    SampleData(int stackIndex, int linkIndex, long value, long timestampNanos) {
      this.stackIndex = stackIndex;
      this.linkIndex = linkIndex;
      this.value = value;
      this.timestampNanos = timestampNanos;
    }
  }

  /**
   * Adds a JFR recording to the conversion.
   *
   * <p>Uses the file path directly if available (via {@link RecordingData#getFile()}), avoiding an
   * unnecessary stream copy. Falls back to stream-based processing otherwise.
   *
   * @param recordingData the recording data to add
   * @return this converter for method chaining
   * @throws IOException if reading JFR data fails
   */
  public JfrToOtlpConverter addRecording(RecordingData recordingData) throws IOException {
    Path file = recordingData.getFile();
    if (file != null) {
      return addFile(file, recordingData.getStart(), recordingData.getEnd());
    }
    try (InputStream stream = recordingData.getStream()) {
      return addStream(stream, recordingData.getStart(), recordingData.getEnd());
    }
  }

  /**
   * Adds a JFR file to the conversion.
   *
   * @param jfrFile path to the JFR file
   * @param start recording start time
   * @param end recording end time
   * @return this converter for method chaining
   */
  public JfrToOtlpConverter addFile(Path jfrFile, Instant start, Instant end) {
    return addPathEntry(new PathEntry(jfrFile, false), start, end);
  }

  /**
   * Adds a JFR stream to the conversion.
   *
   * <p>Note: This method copies the stream to a temporary file since the parser requires file
   * access. When possible, use {@link #addFile(Path, Instant, Instant)} directly.
   *
   * @param jfrStream input stream containing JFR data
   * @param start recording start time
   * @param end recording end time
   * @return this converter for method chaining
   * @throws IOException if reading JFR data fails
   */
  public JfrToOtlpConverter addStream(InputStream jfrStream, Instant start, Instant end)
      throws IOException {
    Path tempFile = Files.createTempFile("jfr-convert-", ".jfr");
    Files.copy(jfrStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
    return addPathEntry(new PathEntry(tempFile, true), start, end);
  }

  private JfrToOtlpConverter addPathEntry(PathEntry pathEntry, Instant start, Instant end) {
    updateTimeRange(start, end);
    pathEntries.add(pathEntry);
    return this;
  }

  /**
   * Converts all added JFR recordings to OTLP format.
   *
   * <p>All recordings added via {@link #addRecording}, {@link #addFile}, or {@link #addStream} are
   * merged into a single OTLP ProfilesData message with shared dictionary tables.
   *
   * <p>After this call, the converter is automatically reset and ready for reuse.
   *
   * @param kind output format (PROTO or JSON)
   * @return encoded OTLP ProfilesData bytes in the requested format
   */
  public byte[] convert(Kind kind) throws IOException {
    try {
      for (PathEntry pathEntry : pathEntries) {
        parseJfrEvents(pathEntry.path);
      }
      switch (kind) {
        case JSON:
          return encodeProfilesDataAsJson();
        case PROTO:
        default:
          return encodeProfilesData();
      }
    } finally {
      reset();
    }
  }

  /**
   * Converts all added JFR recordings to OTLP protobuf format.
   *
   * <p>All recordings added via {@link #addRecording}, {@link #addFile}, or {@link #addStream} are
   * merged into a single OTLP ProfilesData message with shared dictionary tables.
   *
   * <p>After this call, the converter is automatically reset and ready for reuse.
   *
   * @return encoded OTLP ProfilesData protobuf bytes
   */
  public byte[] convert() throws IOException {
    return convert(Kind.PROTO);
  }

  /** Resets converter state, discarding any added recordings. */
  public void reset() {
    // remove any ephemeral files even in case of exception
    pathEntries.stream()
        .filter(e -> e.ephemeral)
        .forEach(
            e -> {
              try {
                Files.deleteIfExists(e.path);
              } catch (IOException ignored) {
              }
            });
    pathEntries.clear();
    stringTable.reset();
    functionTable.reset();
    locationTable.reset();
    stackTable.reset();
    linkTable.reset();
    attributeTable.reset();
    stackTraceCache.clear();
    cpuSamples.clear();
    wallSamples.clear();
    allocSamples.clear();
    lockSamples.clear();
    startTimeNanos = 0;
    endTimeNanos = 0;
  }

  private void updateTimeRange(Instant start, Instant end) {
    long startNanos = start.getEpochSecond() * 1_000_000_000L + start.getNano();
    long endNanos = end.getEpochSecond() * 1_000_000_000L + end.getNano();

    if (startTimeNanos == 0 || startNanos < startTimeNanos) {
      startTimeNanos = startNanos;
    }
    if (endNanos > endTimeNanos) {
      endTimeNanos = endNanos;
    }
  }

  private void parseJfrEvents(Path jfrFile) throws IOException {
    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile)) {
      // Register handlers for each event type
      parser.handle(ExecutionSample.class, this::handleExecutionSample);
      parser.handle(MethodSample.class, this::handleMethodSample);
      parser.handle(ObjectSample.class, this::handleObjectSample);
      parser.handle(JavaMonitorEnter.class, this::handleMonitorEnter);
      parser.handle(JavaMonitorWait.class, this::handleMonitorWait);

      parser.run();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private void handleExecutionSample(ExecutionSample event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    int linkIndex = extractLinkIndex(event.spanId(), event.localRootSpanId());
    long timestamp = convertTimestamp(event.startTime(), ctl);

    cpuSamples.add(new SampleData(stackIndex, linkIndex, 1, timestamp));
  }

  private void handleMethodSample(MethodSample event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    int linkIndex = extractLinkIndex(event.spanId(), event.localRootSpanId());
    long timestamp = convertTimestamp(event.startTime(), ctl);

    wallSamples.add(new SampleData(stackIndex, linkIndex, 1, timestamp));
  }

  private void handleObjectSample(ObjectSample event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    int linkIndex = extractLinkIndex(event.spanId(), event.localRootSpanId());
    long timestamp = convertTimestamp(event.startTime(), ctl);
    long size = event.allocationSize();

    allocSamples.add(new SampleData(stackIndex, linkIndex, size, timestamp));
  }

  private void handleMonitorEnter(JavaMonitorEnter event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    long timestamp = convertTimestamp(event.startTime(), ctl);
    long durationNanos = ctl.chunkInfo().asDuration(event.duration()).toNanos();

    lockSamples.add(new SampleData(stackIndex, 0, durationNanos, timestamp));
  }

  private void handleMonitorWait(JavaMonitorWait event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    long timestamp = convertTimestamp(event.startTime(), ctl);
    long durationNanos = ctl.chunkInfo().asDuration(event.duration()).toNanos();

    lockSamples.add(new SampleData(stackIndex, 0, durationNanos, timestamp));
  }

  private JfrStackTrace safeGetStackTrace(java.util.function.Supplier<JfrStackTrace> supplier) {
    try {
      return supplier.get();
    } catch (NullPointerException e) {
      return null;
    }
  }

  private int convertStackTrace(
      java.util.function.Supplier<JfrStackTrace> stackTraceSupplier,
      long stackTraceId,
      Control ctl) {
    // Create cache key from stackTraceId + chunk identity
    // Using System.identityHashCode for chunk since ChunkInfo doesn't override hashCode
    long cacheKey = stackTraceId ^ ((long) System.identityHashCode(ctl.chunkInfo()) << 32);

    // Check cache first - avoid resolving stack trace if cached
    Integer cachedIndex = stackTraceCache.get(cacheKey);
    if (cachedIndex != null) {
      return cachedIndex;
    }

    // Cache miss - resolve and process stack trace
    JfrStackTrace stackTrace = safeGetStackTrace(stackTraceSupplier);
    if (stackTrace == null) {
      stackTraceCache.put(cacheKey, 0);
      return 0;
    }

    JfrStackFrame[] frames = stackTrace.frames();
    if (frames == null || frames.length == 0) {
      stackTraceCache.put(cacheKey, 0);
      return 0;
    }

    int[] locationIndices = new int[frames.length];
    for (int i = 0; i < frames.length; i++) {
      locationIndices[i] = convertFrame(frames[i]);
    }

    int stackIndex = stackTable.intern(locationIndices);
    stackTraceCache.put(cacheKey, stackIndex);
    return stackIndex;
  }

  private int convertFrame(JfrStackFrame frame) {
    if (frame == null) {
      return 0;
    }

    JfrMethod method = frame.method();
    if (method == null) {
      return 0;
    }

    // Get class and method names
    String methodName = method.name();
    JfrClass type = method.type();
    String className = type != null ? type.name() : null;

    // Get line number
    int lineNumber = frame.lineNumber();
    long line = Math.max(lineNumber, 0);

    // Build full name
    String fullName;
    if (className != null && !className.isEmpty()) {
      fullName = className + "." + (methodName != null ? methodName : "");
    } else {
      fullName = methodName != null ? methodName : "";
    }

    // Intern strings
    int nameIndex = stringTable.intern(fullName);
    int classNameIndex = stringTable.intern(className);
    int methodNameIndex = stringTable.intern(methodName);

    // Intern function
    int functionIndex = functionTable.intern(nameIndex, methodNameIndex, classNameIndex, 0);

    // Create location entry
    return locationTable.intern(0, 0, functionIndex, line, 0);
  }

  private int extractLinkIndex(long spanId, long localRootSpanId) {
    if (spanId == 0) {
      return 0;
    }
    return linkTable.intern(localRootSpanId, spanId);
  }

  private long convertTimestamp(long startTimeTicks, Control ctl) {
    if (startTimeTicks == 0) {
      return 0;
    }
    return ctl.chunkInfo().asInstant(startTimeTicks).toEpochMilli() * 1_000_000L;
  }

  private byte[] encodeProfilesData() {
    ProtobufEncoder encoder = new ProtobufEncoder(64 * 1024);

    // ProfilesData message
    // Field 1: resource_profiles (repeated)
    encoder.writeNestedMessage(
        OtlpProtoFields.ProfilesData.RESOURCE_PROFILES, this::encodeResourceProfiles);

    // Field 2: dictionary
    encoder.writeNestedMessage(OtlpProtoFields.ProfilesData.DICTIONARY, this::encodeDictionary);

    return encoder.toByteArray();
  }

  private void encodeResourceProfiles(ProtobufEncoder encoder) {
    // ResourceProfiles message
    // Field 2: scope_profiles (repeated)
    encoder.writeNestedMessage(
        OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES, this::encodeScopeProfiles);
  }

  private void encodeScopeProfiles(ProtobufEncoder encoder) {
    // ScopeProfiles message
    // Field 2: profiles (repeated)
    // Encode each profile type that has samples

    if (!cpuSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> encodeProfile(enc, PROFILE_TYPE_CPU, UNIT_SAMPLES, cpuSamples));
    }

    if (!wallSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> encodeProfile(enc, PROFILE_TYPE_WALL, UNIT_SAMPLES, wallSamples));
    }

    if (!allocSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> encodeProfile(enc, PROFILE_TYPE_ALLOC, UNIT_BYTES, allocSamples));
    }

    if (!lockSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> encodeProfile(enc, PROFILE_TYPE_LOCK, UNIT_NANOSECONDS, lockSamples));
    }
  }

  private void encodeProfile(
      ProtobufEncoder encoder, String profileType, String unit, List<SampleData> samples) {
    // Profile message

    // Field 1: sample_type
    int typeIndex = stringTable.intern(profileType);
    int unitIndex = stringTable.intern(unit);
    encoder.writeNestedMessage(
        OtlpProtoFields.Profile.SAMPLE_TYPE, enc -> encodeValueType(enc, typeIndex, unitIndex));

    // Field 2: samples (repeated)
    for (SampleData sample : samples) {
      encoder.writeNestedMessage(OtlpProtoFields.Profile.SAMPLES, enc -> encodeSample(enc, sample));
    }

    // Field 3: time_unix_nano
    encoder.writeFixed64Field(OtlpProtoFields.Profile.TIME_UNIX_NANO, startTimeNanos);

    // Field 4: duration_nano
    encoder.writeVarintField(OtlpProtoFields.Profile.DURATION_NANO, endTimeNanos - startTimeNanos);

    // Field 5: period_type (same as sample_type for now)
    encoder.writeNestedMessage(
        OtlpProtoFields.Profile.PERIOD_TYPE, enc -> encodeValueType(enc, typeIndex, unitIndex));

    // Field 6: period (1 for count-based)
    encoder.writeVarintField(OtlpProtoFields.Profile.PERIOD, 1);

    // Field 7: profile_id (16 bytes UUID)
    byte[] profileId = generateProfileId();
    encoder.writeBytesField(OtlpProtoFields.Profile.PROFILE_ID, profileId);
  }

  private void encodeValueType(ProtobufEncoder encoder, int typeIndex, int unitIndex) {
    encoder.writeVarintField(OtlpProtoFields.ValueType.TYPE_STRINDEX, typeIndex);
    encoder.writeVarintField(OtlpProtoFields.ValueType.UNIT_STRINDEX, unitIndex);
  }

  private void encodeSample(ProtobufEncoder encoder, SampleData sample) {
    // Field 1: stack_index
    encoder.writeVarintField(OtlpProtoFields.Sample.STACK_INDEX, sample.stackIndex);

    // Field 3: link_index (skip field 2 attribute_indices for now)
    encoder.writeVarintField(OtlpProtoFields.Sample.LINK_INDEX, sample.linkIndex);

    // Field 4: values (packed)
    encoder.writePackedVarintField(OtlpProtoFields.Sample.VALUES, new long[] {sample.value});

    // Field 5: timestamps_unix_nano (packed)
    if (sample.timestampNanos > 0) {
      encoder.writePackedFixed64Field(
          OtlpProtoFields.Sample.TIMESTAMPS_UNIX_NANO, new long[] {sample.timestampNanos});
    }
  }

  private void encodeDictionary(ProtobufEncoder encoder) {
    // ProfilesDictionary message

    // Field 2: location_table
    for (int i = 1; i < locationTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.LOCATION_TABLE, enc -> encodeLocation(enc, idx));
    }

    // Field 3: function_table
    for (int i = 1; i < functionTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.FUNCTION_TABLE, enc -> encodeFunction(enc, idx));
    }

    // Field 4: link_table
    for (int i = 1; i < linkTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.LINK_TABLE, enc -> encodeLink(enc, idx));
    }

    // Field 5: string_table (repeated strings)
    for (String s : stringTable.getStrings()) {
      encoder.writeStringField(OtlpProtoFields.ProfilesDictionary.STRING_TABLE, s);
    }

    // Field 7: stack_table
    for (int i = 1; i < stackTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.STACK_TABLE, enc -> encodeStack(enc, idx));
    }
  }

  private void encodeLocation(ProtobufEncoder encoder, int index) {
    LocationTable.LocationEntry entry = locationTable.get(index);

    // Field 1: mapping_index
    encoder.writeVarintField(OtlpProtoFields.Location.MAPPING_INDEX, entry.mappingIndex);

    // Field 2: address
    encoder.writeVarintField(OtlpProtoFields.Location.ADDRESS, entry.address);

    // Field 3: lines (repeated)
    for (LocationTable.LineEntry line : entry.lines) {
      encoder.writeNestedMessage(OtlpProtoFields.Location.LINES, enc -> encodeLine(enc, line));
    }
  }

  private void encodeLine(ProtobufEncoder encoder, LocationTable.LineEntry line) {
    encoder.writeVarintField(OtlpProtoFields.Line.FUNCTION_INDEX, line.functionIndex);
    encoder.writeVarintField(OtlpProtoFields.Line.LINE, line.line);
    encoder.writeVarintField(OtlpProtoFields.Line.COLUMN, line.column);
  }

  private void encodeFunction(ProtobufEncoder encoder, int index) {
    FunctionTable.FunctionEntry entry = functionTable.get(index);

    encoder.writeVarintField(OtlpProtoFields.Function.NAME_STRINDEX, entry.nameIndex);
    encoder.writeVarintField(OtlpProtoFields.Function.SYSTEM_NAME_STRINDEX, entry.systemNameIndex);
    encoder.writeVarintField(OtlpProtoFields.Function.FILENAME_STRINDEX, entry.filenameIndex);
    encoder.writeVarintField(OtlpProtoFields.Function.START_LINE, entry.startLine);
  }

  private void encodeLink(ProtobufEncoder encoder, int index) {
    LinkTable.LinkEntry entry = linkTable.get(index);

    encoder.writeBytesField(OtlpProtoFields.Link.TRACE_ID, entry.traceId);
    encoder.writeBytesField(OtlpProtoFields.Link.SPAN_ID, entry.spanId);
  }

  private void encodeStack(ProtobufEncoder encoder, int index) {
    StackTable.StackEntry entry = stackTable.get(index);

    encoder.writePackedVarintField(OtlpProtoFields.Stack.LOCATION_INDICES, entry.locationIndices);
  }

  private byte[] generateProfileId() {
    UUID uuid = UUID.randomUUID();
    byte[] bytes = new byte[16];
    long msb = uuid.getMostSignificantBits();
    long lsb = uuid.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) ((msb >> (56 - i * 8)) & 0xFF);
      bytes[i + 8] = (byte) ((lsb >> (56 - i * 8)) & 0xFF);
    }
    return bytes;
  }

  // JSON encoding methods

  private byte[] encodeProfilesDataAsJson() {
    JsonWriter json = new JsonWriter();
    json.beginObject();

    // resource_profiles array
    json.name("resource_profiles").beginArray();
    encodeResourceProfilesJson(json);
    json.endArray();

    // dictionary
    json.name("dictionary");
    encodeDictionaryJson(json);

    json.endObject();
    return json.toByteArray();
  }

  private void encodeResourceProfilesJson(JsonWriter json) {
    json.beginObject();

    // scope_profiles array
    json.name("scope_profiles").beginArray();
    encodeScopeProfilesJson(json);
    json.endArray();

    json.endObject();
  }

  private void encodeScopeProfilesJson(JsonWriter json) {
    json.beginObject();

    // profiles array
    json.name("profiles").beginArray();

    if (!cpuSamples.isEmpty()) {
      encodeProfileJson(json, PROFILE_TYPE_CPU, UNIT_SAMPLES, cpuSamples);
    }

    if (!wallSamples.isEmpty()) {
      encodeProfileJson(json, PROFILE_TYPE_WALL, UNIT_SAMPLES, wallSamples);
    }

    if (!allocSamples.isEmpty()) {
      encodeProfileJson(json, PROFILE_TYPE_ALLOC, UNIT_BYTES, allocSamples);
    }

    if (!lockSamples.isEmpty()) {
      encodeProfileJson(json, PROFILE_TYPE_LOCK, UNIT_NANOSECONDS, lockSamples);
    }

    json.endArray();
    json.endObject();
  }

  private void encodeProfileJson(
      JsonWriter json, String profileType, String unit, List<SampleData> samples) {
    json.beginObject();

    // sample_type
    int typeIndex = stringTable.intern(profileType);
    int unitIndex = stringTable.intern(unit);
    json.name("sample_type");
    encodeValueTypeJson(json, typeIndex, unitIndex);

    // samples array
    json.name("samples").beginArray();
    for (SampleData sample : samples) {
      encodeSampleJson(json, sample);
    }
    json.endArray();

    // time_unix_nano
    json.name("time_unix_nano").value(startTimeNanos);

    // duration_nano
    json.name("duration_nano").value(endTimeNanos - startTimeNanos);

    // period_type
    json.name("period_type");
    encodeValueTypeJson(json, typeIndex, unitIndex);

    // period
    json.name("period").value(1);

    // profile_id (as hex string for readability)
    byte[] profileId = generateProfileId();
    StringBuilder hexId = new StringBuilder(32);
    for (byte b : profileId) {
      hexId.append(String.format("%02x", b));
    }
    json.name("profile_id").value(hexId.toString());

    json.endObject();
  }

  private void encodeValueTypeJson(JsonWriter json, int typeIndex, int unitIndex) {
    json.beginObject();
    json.name("type_strindex").value(typeIndex);
    json.name("unit_strindex").value(unitIndex);
    json.endObject();
  }

  private void encodeSampleJson(JsonWriter json, SampleData sample) {
    json.beginObject();

    // stack_index
    json.name("stack_index").value(sample.stackIndex);

    // link_index
    if (sample.linkIndex > 0) {
      json.name("link_index").value(sample.linkIndex);
    }

    // values array
    json.name("values").beginArray().value(sample.value).endArray();

    // timestamps_unix_nano array
    if (sample.timestampNanos > 0) {
      json.name("timestamps_unix_nano").beginArray().value(sample.timestampNanos).endArray();
    }

    json.endObject();
  }

  private void encodeDictionaryJson(JsonWriter json) {
    json.beginObject();

    // location_table array
    json.name("location_table").beginArray();
    for (int i = 1; i < locationTable.size(); i++) {
      encodeLocationJson(json, i);
    }
    json.endArray();

    // function_table array
    json.name("function_table").beginArray();
    for (int i = 1; i < functionTable.size(); i++) {
      encodeFunctionJson(json, i);
    }
    json.endArray();

    // link_table array
    json.name("link_table").beginArray();
    for (int i = 1; i < linkTable.size(); i++) {
      encodeLinkJson(json, i);
    }
    json.endArray();

    // string_table array
    json.name("string_table").beginArray();
    for (String s : stringTable.getStrings()) {
      json.value(s);
    }
    json.endArray();

    // stack_table array
    json.name("stack_table").beginArray();
    for (int i = 1; i < stackTable.size(); i++) {
      encodeStackJson(json, i);
    }
    json.endArray();

    json.endObject();
  }

  private void encodeLocationJson(JsonWriter json, int index) {
    LocationTable.LocationEntry entry = locationTable.get(index);
    json.beginObject();

    // mapping_index
    json.name("mapping_index").value(entry.mappingIndex);

    // address
    json.name("address").value(entry.address);

    // lines array
    json.name("lines").beginArray();
    for (LocationTable.LineEntry line : entry.lines) {
      encodeLineJson(json, line);
    }
    json.endArray();

    json.endObject();
  }

  private void encodeLineJson(JsonWriter json, LocationTable.LineEntry line) {
    json.beginObject();
    json.name("function_index").value(line.functionIndex);
    json.name("line").value(line.line);
    if (line.column > 0) {
      json.name("column").value(line.column);
    }
    json.endObject();
  }

  private void encodeFunctionJson(JsonWriter json, int index) {
    FunctionTable.FunctionEntry entry = functionTable.get(index);
    json.beginObject();

    json.name("name_strindex").value(entry.nameIndex);
    json.name("system_name_strindex").value(entry.systemNameIndex);
    json.name("filename_strindex").value(entry.filenameIndex);
    if (entry.startLine > 0) {
      json.name("start_line").value(entry.startLine);
    }

    json.endObject();
  }

  private void encodeLinkJson(JsonWriter json, int index) {
    LinkTable.LinkEntry entry = linkTable.get(index);
    json.beginObject();

    // Encode trace_id and span_id as hex strings for readability
    StringBuilder traceIdHex = new StringBuilder(32);
    for (byte b : entry.traceId) {
      traceIdHex.append(String.format("%02x", b));
    }
    json.name("trace_id").value(traceIdHex.toString());

    StringBuilder spanIdHex = new StringBuilder(16);
    for (byte b : entry.spanId) {
      spanIdHex.append(String.format("%02x", b));
    }
    json.name("span_id").value(spanIdHex.toString());

    json.endObject();
  }

  private void encodeStackJson(JsonWriter json, int index) {
    StackTable.StackEntry entry = stackTable.get(index);
    json.beginObject();

    // location_indices array
    json.name("location_indices").beginArray();
    for (int locationIndex : entry.locationIndices) {
      json.value(locationIndex);
    }
    json.endArray();

    json.endObject();
  }
}
