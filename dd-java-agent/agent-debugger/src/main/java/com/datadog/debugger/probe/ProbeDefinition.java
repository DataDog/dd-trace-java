package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Generic class storing common probe definition */
public abstract class ProbeDefinition implements ProbeImplementation {
  protected static final String LANGUAGE = "java";

  protected final String language;
  protected final String id;
  protected final int version;
  protected transient ProbeId probeId;
  protected final Tag[] tags;
  protected final Map<String, String> tagMap = new HashMap<>();
  protected final Where where;
  protected final MethodLocation evaluateAt;
  protected transient ProbeLocation location;

  protected ProbeDefinition(
      String language, ProbeId probeId, String[] tagStrs, Where where, MethodLocation evaluateAt) {
    this(language, probeId, Tag.fromStrings(tagStrs), where, evaluateAt);
  }

  protected ProbeDefinition(
      String language, ProbeId probeId, Tag[] tags, Where where, MethodLocation evaluateAt) {
    this.language = language;
    this.id = probeId != null ? probeId.getId() : null;
    this.version = probeId != null ? probeId.getVersion() : 0;
    this.probeId = probeId;
    this.tags = tags;
    initTagMap(tagMap, tags);
    this.where = where;
    this.evaluateAt = evaluateAt;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public ProbeId getProbeId() {
    if (probeId == null) {
      probeId = new ProbeId(id, version);
    }
    return probeId;
  }

  public String getLanguage() {
    return language;
  }

  public Tag[] getTags() {
    return tags;
  }

  @Override
  public String getStrTags() {
    return concatTags();
  }

  public String concatTags() {
    if (tags == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (Tag tag : tags) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(tag);
    }
    return sb.toString();
  }

  public Map<String, String> getTagMap() {
    if (tagMap.isEmpty() && tags != null) {
      initTagMap(tagMap, tags);
    }
    return Collections.unmodifiableMap(tagMap);
  }

  public Where getWhere() {
    return where;
  }

  public MethodLocation getEvaluateAt() {
    return evaluateAt;
  }

  public void buildLocation(MethodInfo methodInfo) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    if (methodInfo != null) {
      type = methodInfo.getTypeName();
      method = methodInfo.getMethodName();
    }
    List<String> lines = where.getLines() != null ? Arrays.asList(where.getLines()) : null;
    this.location = new ProbeLocation(type, method, where.getSourceFile(), lines);
  }

  private static void initTagMap(Map<String, String> tagMap, Tag[] tags) {
    tagMap.clear();
    if (tags != null) {
      for (Tag tag : tags) {
        tagMap.put(tag.getKey(), tag.getValue());
      }
    }
  }

  public abstract InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<Integer> probeIndices);

  @Override
  public ProbeLocation getLocation() {
    return location;
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {}

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {}

  /** Commit snapshot based on line context and the current probe This is for line probes */
  @Override
  public void commit(CapturedContext lineContext, int line) {}

  @Override
  public boolean isCaptureSnapshot() {
    return false;
  }

  @Override
  public boolean hasCondition() {
    return false;
  }

  @Override
  public CapturedContext.Status createStatus() {
    return null;
  }

  public boolean isLineProbe() {
    Where.SourceLine[] sourceLines = where.getSourceLines();
    return sourceLines != null && sourceLines.length > 0;
  }

  public abstract static class Builder<T extends Builder> {
    protected String language = LANGUAGE;
    protected ProbeId probeId;
    protected String[] tagStrs;
    protected Where where;
    protected MethodLocation evaluateAt = MethodLocation.DEFAULT;

    public T language(String language) {
      this.language = language;
      return (T) this;
    }

    public T probeId(String id, int version) {
      this.probeId = new ProbeId(id, version);
      return (T) this;
    }

    public T probeId(ProbeId probeId) {
      this.probeId = probeId;
      return (T) this;
    }

    public T tags(String... tagStrs) {
      this.tagStrs = tagStrs;
      return (T) this;
    }

    public T where(Where where) {
      this.where = where;
      return (T) this;
    }

    public T evaluateAt(MethodLocation evaluateAt) {
      this.evaluateAt = evaluateAt;
      return (T) this;
    }

    public T where(String typeName, String methodName) {
      return where(new Where(typeName, methodName, null, (Where.SourceLine[]) null, null));
    }

    public T where(String typeName, String methodName, String signature) {
      return where(new Where(typeName, methodName, signature, (Where.SourceLine[]) null, null));
    }

    public T where(String typeName, String methodName, String signature, String... lines) {
      return where(new Where(typeName, methodName, signature, Where.sourceLines(lines), null));
    }

    public T where(String sourceFile, int line) {
      return where(new Where(null, null, null, new String[] {String.valueOf(line)}, sourceFile));
    }

    public T where(String sourceFile, int lineFrom, int lineTill) {
      return where(
          new Where(
              null,
              null,
              null,
              new Where.SourceLine[] {new Where.SourceLine(lineFrom, lineTill)},
              sourceFile));
    }

    public T where(
        String typeName, String methodName, String signature, int codeLine, String source) {
      return where(
          new Where(
              typeName,
              methodName,
              signature,
              new Where.SourceLine[] {new Where.SourceLine(codeLine)},
              source));
    }

    public T where(
        String typeName,
        String methodName,
        String signature,
        int codeLineFrom,
        int codeLineTill,
        String source) {
      return where(
          new Where(
              typeName,
              methodName,
              signature,
              new Where.SourceLine[] {new Where.SourceLine(codeLineFrom, codeLineTill)},
              source));
    }
  }

  public static final class Tag {
    private final String key;
    private final String value;

    public Tag(String key) {
      this(key, null);
    }

    public Tag(String key, String value) {
      this.key = key;
      this.value = value;
    }

    public String getKey() {
      return key;
    }

    public String getValue() {
      return value;
    }

    public boolean hasValue() {
      return value != null;
    }

    @Override
    public String toString() {
      if (hasValue()) {
        return key + ":" + value;
      } else {
        return key;
      }
    }

    public static Tag fromString(String def) {
      int delimIndex = def.indexOf(':');
      if (delimIndex == -1) {
        return new Tag(def.trim(), null);
      } else {
        String key = def.substring(0, delimIndex).trim();
        String value = def.substring(delimIndex + 1).trim();
        return new Tag(key, value);
      }
    }

    public static Tag[] fromStrings(String[] tagStrs) {
      Tag[] tags = null;
      if (tagStrs != null) {
        tags = new ProbeDefinition.Tag[tagStrs.length];
        for (int i = 0; i < tagStrs.length; i++) {
          tags[i] = fromString(tagStrs[i]);
        }
      }
      return tags;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Tag tag = (Tag) o;
      return Objects.equals(key, tag.key) && Objects.equals(value, tag.value);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(key, value);
    }
  }

  public static class TagAdapter extends JsonAdapter<Tag[]> {
    private static String[] STRING_ARRAY = new String[0];

    @Override
    public Tag[] fromJson(JsonReader jsonReader) throws IOException {
      if (jsonReader.peek() == JsonReader.Token.NULL) {
        jsonReader.nextNull();
        return null;
      }
      List<String> tags = new ArrayList<>();
      jsonReader.beginArray();
      while (jsonReader.hasNext()) {
        tags.add(jsonReader.nextString());
      }
      jsonReader.endArray();
      return Tag.fromStrings(tags.toArray(STRING_ARRAY));
    }

    @Override
    public void toJson(JsonWriter jsonWriter, Tag[] tags) throws IOException {
      if (tags == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginArray();
      for (Tag tag : tags) {
        jsonWriter.value(tag.toString());
      }
      jsonWriter.endArray();
    }
  }
}
