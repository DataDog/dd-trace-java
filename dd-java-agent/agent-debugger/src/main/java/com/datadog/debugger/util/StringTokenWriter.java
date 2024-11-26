package com.datadog.debugger.util;

import static com.datadog.debugger.util.MoshiSnapshotHelper.REDACTED_IDENT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.REDACTED_TYPE_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.TIMEOUT_REASON;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Implementation of {@link com.datadog.debugger.util.SerializerWithLimits.TokenWriter} for String
 * representation. Only works up to 1 max reference depth
 */
public class StringTokenWriter implements SerializerWithLimits.TokenWriter {
  private final StringBuilder sb;
  private final List<EvaluationError> evalErrors;
  private boolean initial;
  private boolean inCollection;
  private boolean inMapEntry;

  public StringTokenWriter(StringBuilder sb, List<EvaluationError> evalErrors) {
    this.sb = sb;
    this.evalErrors = evalErrors;
  }

  @Override
  public void prologue(Object value, String type) {
    if (inMapEntry && !initial) {
      sb.append('=');
    } else if (inCollection && !initial) {
      sb.append(", ");
    }
    initial = false;
  }

  @Override
  public void epilogue(Object value) {}

  @Override
  public void nullValue() {
    sb.append("null");
  }

  @Override
  public void string(String value, boolean isComplete, int originalLength) {
    sb.append(value).append(isComplete ? "" : "...");
  }

  @Override
  public void primitiveValue(Object value) {
    String typeName = value.getClass().getTypeName();
    Function<Object, String> toString = WellKnownClasses.getSafeToString(typeName);
    if (toString == null) {
      throw new UnsupportedOperationException("Cannot convert value from type: " + typeName);
    }
    sb.append(toString.apply(value));
  }

  @Override
  public void arrayPrologue(Object value) {
    sb.append('[');
    initial = true;
    inCollection = true;
  }

  @Override
  public void arrayEpilogue(Object value, boolean isComplete, int arraySize) {
    sb.append(isComplete ? "]" : ", ...]");
    inCollection = false;
  }

  @Override
  public void primitiveArrayElement(String value, String type) {
    if (inCollection && !initial) {
      sb.append(", ");
    }
    sb.append(value);
    initial = false;
  }

  @Override
  public void collectionPrologue(Object value) {
    sb.append('[');
    initial = true;
    inCollection = true;
  }

  @Override
  public void collectionEpilogue(Object value, boolean isComplete, int size) {
    sb.append(isComplete ? "]" : ", ...]");
    inCollection = false;
  }

  @Override
  public void mapPrologue(Object value) {
    sb.append('{');
    initial = true;
    inCollection = true;
  }

  @Override
  public void mapEntryPrologue(Map.Entry<?, ?> entry) {
    if (!initial) {
      sb.append(", ");
    }
    sb.append('[');
    initial = true;
    inMapEntry = true;
  }

  @Override
  public void mapEntryEpilogue(Map.Entry<?, ?> entry) {
    sb.append(']');
    inMapEntry = false;
  }

  @Override
  public void mapEpilogue(boolean isComplete, int size) {
    sb.append(isComplete ? "}" : ", ...}");
    inCollection = false;
  }

  @Override
  public void objectPrologue(Object value) {
    sb.append('{');
    initial = true;
  }

  @Override
  public void objectFieldPrologue(String fieldName, Object value, int maxDepth) {
    if (!initial) {
      sb.append(", ");
    }
    initial = false;
    sb.append(fieldName).append('=');
  }

  @Override
  public void objectEpilogue(Object value) {
    sb.append('}');
  }

  @Override
  public void handleFieldException(Exception ex, Field field) {
    fieldNotCaptured("Cannot extract field: " + ex.toString(), field);
  }

  @Override
  public void fieldNotCaptured(String reason, Field field) {
    if (!initial) {
      sb.append(", ");
    }
    initial = false;
    sb.append(field.getName()).append('=').append(Value.undefinedValue());
    evalErrors.add(new EvaluationError(field.getName(), reason));
  }

  @Override
  public void notCaptured(SerializerWithLimits.NotCapturedReason reason) {
    switch (reason) {
      case MAX_DEPTH:
        sb.append("...");
        break;
      case TIMEOUT:
        sb.append('{').append(TIMEOUT_REASON).append('}');
        break;
      case FIELD_COUNT:
        sb.append(", ...");
        break;
      case REDACTED_IDENT:
        sb.append('{').append(REDACTED_IDENT_REASON).append('}');
        break;
      case REDACTED_TYPE:
        sb.append('{').append(REDACTED_TYPE_REASON).append('}');
        break;
      default:
        throw new RuntimeException("Unsupported NotCapturedReason: " + reason);
    }
  }

  @Override
  public void notCaptured(String reason) throws Exception {
    sb.append("(Error: ").append(reason).append(')');
  }
}
