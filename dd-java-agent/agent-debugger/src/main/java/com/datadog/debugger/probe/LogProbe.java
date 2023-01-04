package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.SnapshotInstrumentor;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Stores definition of a log probe */
public class LogProbe extends ProbeDefinition {

  /** Stores part of a templated message either a str or an expression */
  public static class Segment {
    private final String str;
    private final ValueScript parsedExpr;

    public Segment(String str) {
      this.str = str;
      this.parsedExpr = null;
    }

    public Segment(ValueScript valueScript) {
      this.str = null;
      this.parsedExpr = valueScript;
    }

    public String getStr() {
      return str;
    }

    public String getExpr() {
      return parsedExpr != null ? parsedExpr.getDsl() : null;
    }

    public ValueScript getParsedExpr() {
      return parsedExpr;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Segment segment = (Segment) o;
      return Objects.equals(str, segment.str) && Objects.equals(parsedExpr, segment.parsedExpr);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(str, parsedExpr);
    }

    @Generated
    @Override
    public String toString() {
      return "Segment{" + "str='" + str + '\'' + ", parsedExr=" + parsedExpr + '}';
    }

    public static class SegmentJsonAdapter extends JsonAdapter<Segment> {
      private final ValueScript.ValueScriptAdapter valueScriptAdapter =
          new ValueScript.ValueScriptAdapter();

      @Override
      public Segment fromJson(JsonReader reader) throws IOException {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
          throw new IOException("Invalid Segment format, expect Json object");
        }
        JsonReader peekReader = reader.peekJson();
        peekReader.beginObject();
        Segment segment;
        String fieldName = peekReader.nextName();
        if ("str".equals(fieldName)) {
          reader.beginObject();
          reader.nextName(); // consume str
          segment = new Segment(reader.nextString());
          reader.endObject();
        } else {
          segment = new Segment(valueScriptAdapter.fromJson(reader));
        }
        return segment;
      }

      @Override
      public void toJson(JsonWriter writer, Segment value) throws IOException {
        if (value == null) {
          writer.nullValue();
          return;
        }
        if (value.str != null) {
          writer.beginObject();
          writer.name("str");
          writer.value(value.str);
          writer.endObject();
        } else {
          valueScriptAdapter.toJson(writer, value.parsedExpr);
        }
      }
    }
  }

  private final String template;
  private final List<Segment> segments;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public LogProbe() {
    this(LANGUAGE, null, true, null, null, MethodLocation.DEFAULT, null, new ArrayList<>());
  }

  public LogProbe(
      String language,
      String id,
      boolean active,
      String[] tagStrs,
      Where where,
      MethodLocation evaluateAt,
      String template,
      List<Segment> segments) {
    super(language, id, active, tagStrs, where, evaluateAt);
    this.template = template;
    this.segments = segments;
  }

  public String getTemplate() {
    return template;
  }

  public List<Segment> getSegments() {
    return segments;
  }

  @Override
  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    new SnapshotInstrumentor(this, classLoader, classNode, methodNode, diagnostics).instrument();
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LogProbe that = (LogProbe) o;
    return active == that.active
        && Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt)
        && Objects.equals(template, that.template)
        && Objects.equals(segments, that.segments);
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, active, tagMap, where, evaluateAt, template, segments);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return "LogProbe{"
        + "language='"
        + language
        + '\''
        + ", id='"
        + id
        + '\''
        + ", active="
        + active
        + ", tags="
        + Arrays.toString(tags)
        + ", tagMap="
        + tagMap
        + ", where="
        + where
        + ", evaluateAt="
        + evaluateAt
        + ", template='"
        + template
        + '\''
        + ", segments="
        + segments
        + "} ";
  }

  public static LogProbe.Builder builder() {
    return new Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    private String template;
    private List<Segment> segments;

    public Builder template(String template) {
      this.template = template;
      this.segments = parseTemplate(template);
      return this;
    }

    public LogProbe build() {
      return new LogProbe(
          language, probeId, active, tagStrs, where, evaluateAt, template, segments);
    }

    private static List<Segment> parseTemplate(String template) {
      if (template == null) {
        return Collections.emptyList();
      }
      List<Segment> result = new ArrayList<>();
      int currentIdx = 0;
      int startStrIdx = 0;
      do {
        int startIdx = template.indexOf('{', currentIdx);
        if (startIdx == -1) {
          addStrSegment(result, template.substring(startStrIdx));
          return result;
        }
        if (startIdx + 1 < template.length() && template.charAt(startIdx + 1) == '{') {
          currentIdx = startIdx + 2;
          continue;
        }
        int endIdx = template.indexOf('}', startIdx);
        if (endIdx == -1) {
          addStrSegment(result, template.substring(startStrIdx));
          currentIdx = startIdx + 1;
          startStrIdx = currentIdx;
        } else {
          if (startStrIdx != startIdx) {
            addStrSegment(result, template.substring(startStrIdx, startIdx));
          }
          String expr = template.substring(startIdx + 1, endIdx);

          result.add(new Segment(new ValueScript(ValueScript.parseRefPath(expr), expr)));
          currentIdx = endIdx + 1;
          startStrIdx = currentIdx;
        }
      } while (currentIdx < template.length());
      return result;
    }

    private static void addStrSegment(List<Segment> segments, String str) {
      str = Strings.replace(str, "{{", "{");
      str = Strings.replace(str, "}}", "}");
      segments.add(new Segment(str));
    }
  }
}
