package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ValueScript;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.util.Strings;
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
    private final String expr;
    private final ValueScript parsedExr;

    public Segment(String str) {
      this.str = str;
      this.expr = null;
      this.parsedExr = null;
    }

    public Segment(String expr, ValueScript valueScript) {
      this.str = null;
      this.expr = expr;
      this.parsedExr = valueScript;
    }

    public String getStr() {
      return str;
    }

    public String getExpr() {
      return expr;
    }

    public ValueScript getParsedExr() {
      return parsedExr;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Segment segment = (Segment) o;
      return Objects.equals(str, segment.str)
          && Objects.equals(expr, segment.expr)
          && Objects.equals(parsedExr, segment.parsedExr);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(str, expr, parsedExr);
    }

    @Generated
    @Override
    public String toString() {
      return "Segment{"
          + "str='"
          + str
          + '\''
          + ", expr='"
          + expr
          + '\''
          + ", parsedExr="
          + parsedExr
          + '}';
    }
  }

  private final String template;
  private final List<Segment> segments;

  public LogProbe(
      String language,
      String id,
      boolean active,
      String[] tagStrs,
      Where where,
      String template,
      List<Segment> segments) {
    super(language, id, active, tagStrs, where);
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
      List<DiagnosticMessage> diagnostics) {}

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
        && Objects.equals(template, that.template)
        && Objects.equals(segments, that.segments);
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, active, tagMap, where, template, segments);
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
        + ", template='"
        + template
        + '\''
        + ", segments="
        + segments
        + "} ";
  }

  public static LogProbe.Builder builder() {
    return new LogProbe.Builder();
  }

  public static class Builder {
    private String language = LANGUAGE;
    private String logId;
    private boolean active = true;
    private String[] tagStrs;
    private Where where;
    private String template;
    private List<Segment> segments;

    public LogProbe.Builder language(String language) {
      this.language = language;
      return this;
    }

    public LogProbe.Builder logId(String logId) {
      this.logId = logId;
      return this;
    }

    public LogProbe.Builder active(boolean active) {
      this.active = active;
      return this;
    }

    public LogProbe.Builder tags(String... tagStrs) {
      this.tagStrs = tagStrs;
      return this;
    }

    public LogProbe.Builder where(Where where) {
      this.where = where;
      return this;
    }

    public LogProbe.Builder where(String typeName, String methodName) {
      return where(new Where(typeName, methodName, null, (Where.SourceLine[]) null, null));
    }

    public LogProbe.Builder where(String typeName, String methodName, String signature) {
      return where(new Where(typeName, methodName, signature, (Where.SourceLine[]) null, null));
    }

    public LogProbe.Builder where(
        String typeName, String methodName, String signature, String... lines) {
      return where(new Where(typeName, methodName, signature, Where.sourceLines(lines), null));
    }

    public LogProbe.Builder where(String sourceFile, String... lines) {
      return where(new Where(null, null, null, lines, sourceFile));
    }

    public LogProbe.Builder where(
        String typeName, String methodName, String signature, int codeLine, String source) {
      return where(
          new Where(
              typeName,
              methodName,
              signature,
              new Where.SourceLine[] {new Where.SourceLine(codeLine)},
              source));
    }

    public LogProbe.Builder where(
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

    public LogProbe.Builder template(String template) {
      this.template = template;
      this.segments = parseTemplate(template);
      return this;
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
          result.add(new Segment(expr, new ValueScript(expr)));
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

    public LogProbe build() {
      return new LogProbe(language, logId, active, tagStrs, where, template, segments);
    }
  }
}
