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
    /** JSON text format (compact). */
    JSON,
    /** JSON text format with pretty-printing. */
    JSON_PRETTY
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

  // Original payload support
  private boolean includeOriginalPayload = false;

  /** Holds data for a single sample before encoding. */
  private static final class SampleData {
    final int stackIndex;
    final int linkIndex;
    final long value;
    final long timestampNanos;
    final int[] attributeIndices;

    SampleData(
        int stackIndex, int linkIndex, long value, long timestampNanos, int[] attributeIndices) {
      this.stackIndex = stackIndex;
      this.linkIndex = linkIndex;
      this.value = value;
      this.timestampNanos = timestampNanos;
      this.attributeIndices = attributeIndices != null ? attributeIndices : new int[0];
    }
  }

  /**
   * Enables or disables inclusion of original JFR payload in the OTLP output.
   *
   * <p>When enabled, the original JFR recording bytes are included in the {@code original_payload}
   * field of each Profile message, with {@code original_payload_format} set to "jfr". Multiple JFR
   * files are concatenated into a single "uber-JFR" which is valid per the JFR specification.
   *
   * <p>Default: disabled (as recommended by OTLP spec due to size considerations)
   *
   * @param include true to include original payload, false to exclude
   * @return this converter for method chaining
   */
  public JfrToOtlpConverter setIncludeOriginalPayload(boolean include) {
    this.includeOriginalPayload = include;
    return this;
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
      // Parse events from all files
      for (PathEntry pathEntry : pathEntries) {
        parseJfrEvents(pathEntry.path);
      }

      switch (kind) {
        case JSON:
          return encodeProfilesDataAsJson(false);
        case JSON_PRETTY:
          return encodeProfilesDataAsJson(true);
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

  /**
   * Creates an InputStream that concatenates all added JFR files.
   *
   * <p>JFR format supports concatenating multiple recordings - they will be processed sequentially
   * by JFR parsers. This creates an "uber-JFR" containing all added recordings without copying to
   * memory.
   *
   * @return InputStream over all JFR files, or null if no files added
   */
  private InputStream createJfrPayloadStream() throws IOException {
    if (pathEntries.isEmpty()) {
      return null;
    }

    if (pathEntries.size() == 1) {
      // Single file - just return its stream
      return Files.newInputStream(pathEntries.iterator().next().path);
    }

    // Multiple files - chain them using SequenceInputStream
    java.util.Enumeration<InputStream> streams =
        new java.util.Enumeration<InputStream>() {
          private final java.util.Iterator<PathEntry> iterator = pathEntries.iterator();

          @Override
          public boolean hasMoreElements() {
            return iterator.hasNext();
          }

          @Override
          public InputStream nextElement() {
            try {
              return Files.newInputStream(iterator.next().path);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };

    return new java.io.SequenceInputStream(streams);
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

    int[] attributeIndices = new int[] {getSampleTypeAttributeIndex("cpu")};
    cpuSamples.add(new SampleData(stackIndex, linkIndex, 1, timestamp, attributeIndices));
  }

  private void handleMethodSample(MethodSample event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    int linkIndex = extractLinkIndex(event.spanId(), event.localRootSpanId());
    long timestamp = convertTimestamp(event.startTime(), ctl);

    int[] attributeIndices = new int[] {getSampleTypeAttributeIndex("wall")};
    wallSamples.add(new SampleData(stackIndex, linkIndex, 1, timestamp, attributeIndices));
  }

  private void handleObjectSample(ObjectSample event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    int linkIndex = extractLinkIndex(event.spanId(), event.localRootSpanId());
    long timestamp = convertTimestamp(event.startTime(), ctl);

    // Try to get size and weight fields (new format)
    // Fall back to allocationSize if not available (backwards compatibility)
    long size;
    float weight;
    try {
      size = event.size();
      weight = event.weight();
      if (size == 0 && weight == 0) {
        // Fields exist but are zero - fall back to allocationSize
        size = event.allocationSize();
        weight = 1;
      }
    } catch (Exception e) {
      // Fields don't exist in JFR event - use allocationSize
      size = event.allocationSize();
      weight = 1;
    }

    long upscaledSize = Math.round(size * weight);

    // Build attributes: sample.type + alloc.class (if available)
    int sampleTypeIndex = getSampleTypeAttributeIndex("alloc");
    String className = null;
    try {
      className = event.objectClass();
    } catch (Exception ignored) {
      // objectClass field doesn't exist in this JFR event - skip it
    }

    int[] attributeIndices;
    if (className != null && !className.isEmpty()) {
      int keyIndex = stringTable.intern("alloc.class");
      int classAttrIndex = attributeTable.internString(keyIndex, className, 0);
      attributeIndices = new int[] {sampleTypeIndex, classAttrIndex};
    } else {
      attributeIndices = new int[] {sampleTypeIndex};
    }

    allocSamples.add(
        new SampleData(stackIndex, linkIndex, upscaledSize, timestamp, attributeIndices));
  }

  private void handleMonitorEnter(JavaMonitorEnter event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    long timestamp = convertTimestamp(event.startTime(), ctl);
    long durationNanos = ctl.chunkInfo().asDuration(event.duration()).toNanos();

    int[] attributeIndices = new int[] {getSampleTypeAttributeIndex("lock-contention")};
    lockSamples.add(new SampleData(stackIndex, 0, durationNanos, timestamp, attributeIndices));
  }

  private void handleMonitorWait(JavaMonitorWait event, Control ctl) {
    if (event == null) {
      return;
    }
    int stackIndex = convertStackTrace(event::stackTrace, event.stackTraceId(), ctl);
    long timestamp = convertTimestamp(event.startTime(), ctl);
    long durationNanos = ctl.chunkInfo().asDuration(event.duration()).toNanos();

    int[] attributeIndices = new int[] {getSampleTypeAttributeIndex("lock-contention")};
    lockSamples.add(new SampleData(stackIndex, 0, durationNanos, timestamp, attributeIndices));
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

  private int getSampleTypeAttributeIndex(String sampleType) {
    int keyIndex = stringTable.intern("sample.type");
    int unitIndex = 0; // No unit for string labels
    return attributeTable.internString(keyIndex, sampleType, unitIndex);
  }

  private long convertTimestamp(long startTimeTicks, Control ctl) {
    if (startTimeTicks == 0) {
      return 0;
    }
    return ctl.chunkInfo().asInstant(startTimeTicks).toEpochMilli() * 1_000_000L;
  }

  private byte[] encodeProfilesData() throws IOException {
    ProtobufEncoder encoder = new ProtobufEncoder(64 * 1024);

    // ProfilesData message
    // Field 1: resource_profiles (repeated)
    encoder.writeNestedMessage(
        OtlpProtoFields.ProfilesData.RESOURCE_PROFILES,
        enc -> {
          try {
            encodeResourceProfiles(enc);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });

    // Field 2: dictionary
    encoder.writeNestedMessage(OtlpProtoFields.ProfilesData.DICTIONARY, this::encodeDictionary);

    return encoder.toByteArray();
  }

  private void encodeResourceProfiles(ProtobufEncoder encoder) throws IOException {
    // ResourceProfiles message
    // Field 2: scope_profiles (repeated)
    encoder.writeNestedMessage(
        OtlpProtoFields.ResourceProfiles.SCOPE_PROFILES,
        enc -> {
          try {
            encodeScopeProfiles(enc);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private void encodeScopeProfiles(ProtobufEncoder encoder) throws IOException {
    // ScopeProfiles message
    // Field 2: profiles (repeated)
    // Encode each profile type that has samples

    if (!cpuSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> {
            try {
              encodeProfile(enc, PROFILE_TYPE_CPU, UNIT_SAMPLES, cpuSamples);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }

    if (!wallSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> {
            try {
              encodeProfile(enc, PROFILE_TYPE_WALL, UNIT_SAMPLES, wallSamples);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }

    if (!allocSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> {
            try {
              encodeProfile(enc, PROFILE_TYPE_ALLOC, UNIT_BYTES, allocSamples);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }

    if (!lockSamples.isEmpty()) {
      encoder.writeNestedMessage(
          OtlpProtoFields.ScopeProfiles.PROFILES,
          enc -> {
            try {
              encodeProfile(enc, PROFILE_TYPE_LOCK, UNIT_NANOSECONDS, lockSamples);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  private void encodeProfile(
      ProtobufEncoder encoder, String profileType, String unit, List<SampleData> samples)
      throws IOException {
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

    // Fields 9 & 10: original_payload_format and original_payload (if enabled)
    if (includeOriginalPayload && !pathEntries.isEmpty()) {
      encoder.writeStringField(OtlpProtoFields.Profile.ORIGINAL_PAYLOAD_FORMAT, "jfr");

      // Calculate total size of all JFR files
      long totalSize = 0;
      for (PathEntry entry : pathEntries) {
        totalSize += Files.size(entry.path);
      }

      // Write original_payload from concatenated stream
      encoder.writeBytesField(
          OtlpProtoFields.Profile.ORIGINAL_PAYLOAD, createJfrPayloadStream(), totalSize);
    }
  }

  private void encodeValueType(ProtobufEncoder encoder, int typeIndex, int unitIndex) {
    encoder.writeVarintField(OtlpProtoFields.ValueType.TYPE_STRINDEX, typeIndex);
    encoder.writeVarintField(OtlpProtoFields.ValueType.UNIT_STRINDEX, unitIndex);
  }

  private void encodeSample(ProtobufEncoder encoder, SampleData sample) {
    // Field 1: stack_index
    encoder.writeVarintField(OtlpProtoFields.Sample.STACK_INDEX, sample.stackIndex);

    // Field 2: attribute_indices (packed repeated int32 - proto3 default)
    if (sample.attributeIndices.length > 0) {
      encoder.writePackedVarintField(
          OtlpProtoFields.Sample.ATTRIBUTE_INDICES, sample.attributeIndices);
    }

    // Field 3: link_index
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
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    for (int i = 0; i < locationTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.LOCATION_TABLE, enc -> encodeLocation(enc, idx));
    }

    // Field 3: function_table
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    for (int i = 0; i < functionTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.FUNCTION_TABLE, enc -> encodeFunction(enc, idx));
    }

    // Field 4: link_table
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    for (int i = 0; i < linkTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.LINK_TABLE, enc -> encodeLink(enc, idx));
    }

    // Field 5: string_table (repeated strings)
    for (String s : stringTable.getStrings()) {
      encoder.writeStringField(OtlpProtoFields.ProfilesDictionary.STRING_TABLE, s);
    }

    // Field 6: attribute_table
    // Note: Must always include at least index 0 (null/unset sentinel) required by OTLP spec
    for (int i = 0; i < attributeTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.ATTRIBUTE_TABLE, enc -> encodeAttribute(enc, idx));
    }

    // Field 7: stack_table
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    for (int i = 0; i < stackTable.size(); i++) {
      final int idx = i;
      encoder.writeNestedMessage(
          OtlpProtoFields.ProfilesDictionary.STACK_TABLE, enc -> encodeStack(enc, idx));
    }
  }

  private void encodeLocation(ProtobufEncoder encoder, int index) {
    LocationTable.LocationEntry entry = locationTable.get(index);

    // Field 1: mapping_index
    // Note: Always write, even for index 0 sentinel (value 0) to ensure non-empty message
    encoder.writeVarintField(OtlpProtoFields.Location.MAPPING_INDEX, entry.mappingIndex);

    // Field 2: address
    // Note: For index 0 sentinel, this will be 0 but writeVarintField writes 0 values
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

    // For index 0 (null sentinel), location_indices is empty
    // writePackedVarintField handles empty arrays by writing nothing, but writeNestedMessage
    // now always writes the message envelope (tag + length=0) even if the content is empty
    encoder.writePackedVarintField(OtlpProtoFields.Stack.LOCATION_INDICES, entry.locationIndices);
  }

  private void encodeAttribute(ProtobufEncoder encoder, int index) {
    AttributeTable.AttributeEntry entry = attributeTable.get(index);

    // Field 1: key_strindex
    encoder.writeVarintField(OtlpProtoFields.KeyValueAndUnit.KEY_STRINDEX, entry.keyIndex);

    // Field 2: value (AnyValue oneof)
    encoder.writeNestedMessage(
        OtlpProtoFields.KeyValueAndUnit.VALUE,
        enc -> {
          switch (entry.valueType) {
            case STRING:
              enc.writeStringField(OtlpProtoFields.AnyValue.STRING_VALUE, (String) entry.value);
              break;
            case BOOL:
              enc.writeBoolField(OtlpProtoFields.AnyValue.BOOL_VALUE, (Boolean) entry.value);
              break;
            case INT:
              enc.writeSignedVarintField(OtlpProtoFields.AnyValue.INT_VALUE, (Long) entry.value);
              break;
            case DOUBLE:
              // Note: protobuf doubles are fixed64, not varint
              long doubleBits = Double.doubleToRawLongBits((Double) entry.value);
              enc.writeFixed64Field(OtlpProtoFields.AnyValue.DOUBLE_VALUE, doubleBits);
              break;
          }
        });

    // Field 3: unit_strindex
    encoder.writeVarintField(OtlpProtoFields.KeyValueAndUnit.UNIT_STRINDEX, entry.unitIndex);
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

  private byte[] encodeProfilesDataAsJson(boolean prettyPrint) {
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
    byte[] compactJson = json.toByteArray();

    // Pretty-print if requested
    return prettyPrint ? prettyPrintJson(compactJson) : compactJson;
  }

  /**
   * Pretty-prints compact JSON with indentation.
   *
   * <p>Simple pretty-printer that adds newlines and indentation without external dependencies.
   */
  private byte[] prettyPrintJson(byte[] compactJson) {
    String compact = new String(compactJson, java.nio.charset.StandardCharsets.UTF_8);
    StringBuilder pretty = new StringBuilder(compact.length() + compact.length() / 4);
    int indent = 0;
    boolean inString = false;
    boolean escape = false;

    for (int i = 0; i < compact.length(); i++) {
      char c = compact.charAt(i);

      if (escape) {
        pretty.append(c);
        escape = false;
        continue;
      }

      if (c == '\\') {
        pretty.append(c);
        escape = true;
        continue;
      }

      if (c == '"') {
        pretty.append(c);
        inString = !inString;
        continue;
      }

      if (inString) {
        pretty.append(c);
        continue;
      }

      switch (c) {
        case '{':
        case '[':
          pretty.append(c).append('\n');
          indent++;
          appendIndent(pretty, indent);
          break;
        case '}':
        case ']':
          pretty.append('\n');
          indent--;
          appendIndent(pretty, indent);
          pretty.append(c);
          break;
        case ',':
          pretty.append(c).append('\n');
          appendIndent(pretty, indent);
          break;
        case ':':
          pretty.append(c).append(' ');
          break;
        default:
          if (!Character.isWhitespace(c)) {
            pretty.append(c);
          }
          break;
      }
    }

    return pretty.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private void appendIndent(StringBuilder sb, int indent) {
    for (int i = 0; i < indent * 2; i++) {
      sb.append(' ');
    }
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
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    json.name("location_table").beginArray();
    for (int i = 0; i < locationTable.size(); i++) {
      encodeLocationJson(json, i);
    }
    json.endArray();

    // function_table array
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    json.name("function_table").beginArray();
    for (int i = 0; i < functionTable.size(); i++) {
      encodeFunctionJson(json, i);
    }
    json.endArray();

    // link_table array
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    json.name("link_table").beginArray();
    for (int i = 0; i < linkTable.size(); i++) {
      encodeLinkJson(json, i);
    }
    json.endArray();

    // string_table array
    json.name("string_table").beginArray();
    for (String s : stringTable.getStrings()) {
      json.value(s);
    }
    json.endArray();

    // attribute_table array
    // Note: Must always include at least index 0 (null/unset sentinel) required by OTLP spec
    json.name("attribute_table").beginArray();
    for (int i = 0; i < attributeTable.size(); i++) {
      encodeAttributeJson(json, i);
    }
    json.endArray();

    // stack_table array
    // Note: Include index 0 (null/unset sentinel) required by OTLP spec
    json.name("stack_table").beginArray();
    for (int i = 0; i < stackTable.size(); i++) {
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

  private void encodeAttributeJson(JsonWriter json, int index) {
    AttributeTable.AttributeEntry entry = attributeTable.get(index);
    json.beginObject();

    // key_strindex
    json.name("key_strindex").value(entry.keyIndex);

    // value object (AnyValue)
    json.name("value").beginObject();
    switch (entry.valueType) {
      case STRING:
        json.name("string_value").value((String) entry.value);
        break;
      case BOOL:
        json.name("bool_value").value((Boolean) entry.value);
        break;
      case INT:
        json.name("int_value").value((Long) entry.value);
        break;
      case DOUBLE:
        json.name("double_value").value((Double) entry.value);
        break;
    }
    json.endObject();

    // unit_strindex
    json.name("unit_strindex").value(entry.unitIndex);

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
