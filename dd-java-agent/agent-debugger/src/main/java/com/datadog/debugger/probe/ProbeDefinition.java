package com.datadog.debugger.probe;

import static java.util.Collections.singletonList;

import com.datadog.debugger.agent.Generated;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.SummaryBuilder;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Generic class storing common probe definition */
public abstract class ProbeDefinition implements Snapshot.ProbeDetails {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProbeDefinition.class);
  protected static final String LANGUAGE = "java";

  protected final String language;
  protected final String id;
  protected final int version;
  protected final Tag[] tags;
  protected final Map<String, String> tagMap = new HashMap<>();
  protected final Where where;
  protected final MethodLocation evaluateAt;
  protected transient SummaryBuilder summaryBuilder;
  protected transient Snapshot.ProbeLocation location;

  protected ProbeDefinition(
      String language, ProbeId probeId, String[] tagStrs, Where where, MethodLocation evaluateAt) {
    this(language, probeId, Tag.fromStrings(tagStrs), where, evaluateAt);
  }

  protected ProbeDefinition(
      String language, ProbeId probeId, Tag[] tags, Where where, MethodLocation evaluateAt) {
    this.language = language;
    this.id = probeId != null ? probeId.getId() : null;
    this.version = probeId != null ? probeId.getVersion() : 0;
    this.tags = tags;
    initTagMap(tagMap, tags);
    this.where = where;
    this.evaluateAt = evaluateAt;
  }

  @Override
  public String getId() {
    return id;
  }

  public ProbeId getProbeId() {
    return new ProbeId(id, version);
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
        sb.append(",");
      }
      sb.append(tag);
    }
    return sb.toString();
  }

  public Map<String, String> getTagMap() {
    return Collections.unmodifiableMap(tagMap);
  }

  public Where getWhere() {
    return where;
  }

  public MethodLocation getEvaluateAt() {
    return evaluateAt;
  }

  public void buildLocation(String type, String method) {
    List<String> lines = where.getLines() != null ? Arrays.asList(where.getLines()) : null;
    this.location = new Snapshot.ProbeLocation(type, method, where.getSourceFile(), lines);
  }

  private static void initTagMap(Map<String, String> tagMap, Tag[] tags) {
    tagMap.clear();
    if (tags != null) {
      for (Tag tag : tags) {
        tagMap.put(tag.getKey(), tag.getValue());
      }
    }
  }

  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    instrument(classLoader, classNode, methodNode, diagnostics, singletonList(getId()));
  }

  public abstract void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds);

  @Override
  public Snapshot.ProbeLocation getLocation() {
    return location;
  }

  @Override
  public void evaluate(
      Snapshot.CapturedContext context,
      Snapshot.CapturedContext.Status status,
      MethodLocation methodLocation) {}

  @Override
  public SummaryBuilder getSummaryBuilder() {
    return summaryBuilder;
  }

  @Override
  public boolean isCaptureSnapshot() {
    return false;
  }

  @Override
  public boolean hasCondition() {
    return false;
  }

  protected boolean resolveEvaluateAt(MethodLocation methodLocation) {
    if (methodLocation == MethodLocation.DEFAULT) {
      // line probe, no evaluation of probe's evaluateAt
      return true;
    }
    MethodLocation localEvaluateAt = evaluateAt; // MethodLocation.convert(evaluateAt);
    if (methodLocation == MethodLocation.ENTRY) {
      return localEvaluateAt == MethodLocation.DEFAULT || localEvaluateAt == MethodLocation.ENTRY;
    }
    return localEvaluateAt == methodLocation;
  }

  protected static boolean executeScript(
      DebuggerScript<Boolean> script, Snapshot.CapturedContext capture, String probeId) {
    if (script == null) {
      return true;
    }
    long startTs = System.nanoTime();
    try {
      if (!script.execute(capture)) {
        return false;
      }
    } catch (RuntimeException ex) {
      LOGGER.debug("Evaluation error: ", ex);
      return false;
    } finally {
      LOGGER.debug(
          "Script for probe[{}] evaluated in {}ns", probeId, (System.nanoTime() - startTs));
    }
    return true;
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
