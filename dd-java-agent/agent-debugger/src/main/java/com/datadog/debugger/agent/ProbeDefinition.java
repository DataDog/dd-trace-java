package com.datadog.debugger.agent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Generic class storing common probe definition */
public abstract class ProbeDefinition {
  protected static final String LANGUAGE = "java";

  protected final String language;
  protected final String id;
  protected final boolean active;
  protected final Tag[] tags;
  protected final Map<String, String> tagMap = new HashMap<>();
  protected final Where where;
  // transient for no serialization
  protected final transient List<ProbeDefinition> additionalProbes = new ArrayList<>();

  public ProbeDefinition(
      String language, String id, boolean active, String[] tagStrs, Where where) {
    this.language = language;
    this.id = id;
    this.active = active;
    tags = Tag.fromStrings(tagStrs);
    initTagMap(tagMap, tags);
    this.where = where;
  }

  public String getId() {
    return id;
  }

  public String getLanguage() {
    return language;
  }

  public boolean isActive() {
    return active;
  }

  public Tag[] getTags() {
    return tags;
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

  public void addAdditionalProbe(ProbeDefinition probe) {
    additionalProbes.add(probe);
  }

  public List<ProbeDefinition> getAdditionalProbes() {
    return additionalProbes;
  }

  public Stream<String> getAllProbeIds() {
    return Stream.concat(Stream.of(this), getAdditionalProbes().stream())
        .map(ProbeDefinition::getId);
  }

  private static void initTagMap(Map<String, String> tagMap, Tag[] tags) {
    tagMap.clear();
    if (tags != null) {
      for (Tag tag : tags) {
        tagMap.put(tag.getKey(), tag.getValue());
      }
    }
  }

  public abstract void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics);

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
        tags = new SnapshotProbe.Tag[tagStrs.length];
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
