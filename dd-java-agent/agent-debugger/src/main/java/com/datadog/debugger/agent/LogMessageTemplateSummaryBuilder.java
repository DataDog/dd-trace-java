package com.datadog.debugger.agent;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.util.ValueSerializer;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.FieldExtractor;
import datadog.trace.bootstrap.debugger.Fields;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.SummaryBuilder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogMessageTemplateSummaryBuilder implements SummaryBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(LogMessageTemplateSummaryBuilder.class);

  private final LogProbe logProbe;
  private final List<Snapshot.EvaluationError> evaluationErrors = new ArrayList<>();

  public LogMessageTemplateSummaryBuilder(LogProbe logProbe) {
    this.logProbe = logProbe;
  }

  @Override
  public void addEntry(Snapshot.CapturedContext entry) {
    executeExpressions(entry);
  }

  @Override
  public void addExit(Snapshot.CapturedContext exit) {
    executeExpressions(exit);
  }

  @Override
  public void addLine(Snapshot.CapturedContext line) {
    executeExpressions(line);
  }

  @Override
  public void addStack(List<CapturedStackFrame> stack) {}

  @Override
  public String build() {
    if (logProbe.getSegments() == null) {
      return "This is a dynamically created log message.";
    }
    StringBuilder sb = new StringBuilder();
    for (LogProbe.Segment segment : logProbe.getSegments()) {
      if (segment.getStr() != null) {
        sb.append(segment.getStr());
      } else {
        Value<?> result = segment.getParsedExpr().getResult();
        if (result.isUndefined()) {
          sb.append(result.getValue());
        } else if (result.isNull()) {
          sb.append("null");
        } else {
          Limits limits = new Limits(1, 3, 255, 5);
          serializeValue(sb, segment.getParsedExpr().getDsl(), result.getValue(), limits);
        }
      }
    }
    return sb.toString();
  }

  @Override
  public List<Snapshot.EvaluationError> getEvaluationErrors() {
    return evaluationErrors;
  }

  private void executeExpressions(Snapshot.CapturedContext entry) {
    if (logProbe.getSegments() == null) {
      return;
    }
    for (LogProbe.Segment segment : logProbe.getSegments()) {
      ValueScript parsedExr = segment.getParsedExpr();
      if (parsedExr != null) {
        parsedExr.execute(entry);
      }
    }
  }

  private void serializeValue(StringBuilder sb, String expr, Object value, Limits limits) {
    ValueSerializer serializer =
        new ValueSerializer(new StringTypeSerializer(sb, evaluationErrors));
    try {
      serializer.serialize(
          value, value != null ? value.getClass().getTypeName() : "java.lang.Object", limits);
    } catch (Exception ex) {
      evaluationErrors.add(new Snapshot.EvaluationError(expr, ex.toString()));
    }
  }

  private static class StringTypeSerializer implements ValueSerializer.TypeSerializer {

    private final StringBuilder sb;
    private final List<Snapshot.EvaluationError> evalErrors;
    private boolean initial;
    private boolean inCollection;
    private boolean inMapEntry;

    public StringTypeSerializer(StringBuilder sb, List<Snapshot.EvaluationError> evalErrors) {
      this.sb = sb;
      this.evalErrors = evalErrors;
    }

    @Override
    public void prologue(Object value, String type) throws Exception {
      if (inMapEntry && !initial) {
        sb.append("=");
      } else if (inCollection && !initial) {
        sb.append(", ");
      }
      initial = false;
    }

    @Override
    public void epilogue(Object value) throws Exception {}

    @Override
    public void nullValue() throws Exception {
      sb.append("null");
    }

    @Override
    public void string(String value, boolean isComplete, int originalLength) throws Exception {
      sb.append(value).append(isComplete ? "" : "...");
    }

    @Override
    public void primitiveValue(Object value) throws Exception {
      sb.append(value);
    }

    @Override
    public void arrayPrologue(Object value) throws Exception {
      sb.append('[');
      initial = true;
      inCollection = true;
    }

    @Override
    public void arrayEpilogue(Object value, boolean isComplete, int arraySize) throws Exception {
      sb.append(isComplete ? "]" : ", ...]");
      inCollection = false;
    }

    @Override
    public void primitiveArrayElement(String value, String type) throws Exception {
      if (inCollection && !initial) {
        sb.append(", ");
      }
      sb.append(value);
      initial = false;
    }

    @Override
    public void collectionPrologue(Object value) throws Exception {
      sb.append("[");
      initial = true;
      inCollection = true;
    }

    @Override
    public void collectionEpilogue(Object value, boolean isComplete, int size) throws Exception {
      sb.append(isComplete ? "]" : ", ...]");
      inCollection = false;
    }

    @Override
    public void mapPrologue(Object value) throws Exception {
      sb.append("{");
      initial = true;
      inCollection = true;
    }

    @Override
    public void mapEntryPrologue(Map.Entry<?, ?> entry) throws Exception {
      if (!initial) {
        sb.append(", ");
      }
      sb.append("[");
      initial = true;
      inMapEntry = true;
    }

    @Override
    public void mapEntryEpilogue(Map.Entry<?, ?> entry) throws Exception {
      sb.append("]");
      inMapEntry = false;
    }

    @Override
    public void mapEpilogue(Map<?, ?> map, boolean isComplete) throws Exception {
      sb.append(isComplete ? "}" : ", ...}");
      inCollection = false;
    }

    @Override
    public void objectValue(Object value, ValueSerializer valueSerializer, Limits limits)
        throws Exception {
      sb.append("{");
      initial = true;
      Fields.ProcessField onField =
          (field, val, maxDepth) -> {
            try {
              if (!initial) {
                sb.append(", ");
              }
              initial = false;
              sb.append(field.getName()).append("=");
              Limits newLimits = Limits.decDepthLimits(maxDepth, limits);
              String typeName;
              if (ValueSerializer.isPrimitive(field.getType().getTypeName())) {
                typeName = field.getType().getTypeName();
              } else {
                typeName =
                    val != null ? val.getClass().getTypeName() : field.getType().getTypeName();
              }
              valueSerializer.serialize(
                  val instanceof Snapshot.CapturedValue
                      ? ((Snapshot.CapturedValue) val).getValue()
                      : val,
                  typeName,
                  newLimits);
            } catch (Exception ex) {
              LOG.debug("Exception when extracting field={}", field.getName(), ex);
            }
          };
      FieldExtractor.extract(
          value, limits, onField, this::fieldExceptionHandling, this::maxFieldCount);
      sb.append("}");
    }

    private void fieldExceptionHandling(Exception ex, Field field) {
      if (!initial) {
        sb.append(", ");
      }
      initial = false;
      String fieldName = field.getName();
      sb.append(fieldName).append('=').append(Value.undefinedValue());
      evalErrors.add(
          new Snapshot.EvaluationError(fieldName, "Cannot extract field: " + ex.toString()));
    }

    private void maxFieldCount(Field field) {
      sb.append("...");
    }

    @Override
    public void reachedMaxDepth() throws Exception {
      sb.append("...");
    }
  }
}
