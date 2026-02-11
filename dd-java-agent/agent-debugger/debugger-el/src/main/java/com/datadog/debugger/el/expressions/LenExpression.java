package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Get the 'length' of a {@linkplain Value} instance, if supported.<br>
 * For string and collection values (list or map) the result will correspond to their content. For
 * {@linkplain Value#NULL} the length will always be -1 and for {@linkplain Value#UNDEFINED} it will
 * be 0.
 */
public final class LenExpression implements ValueExpression<Value<? extends Number>> {
  private static final Logger log = LoggerFactory.getLogger(LenExpression.class);

  private ValueExpression<?> source;

  public LenExpression(ValueExpression<?> source) {
    this.source = source;
  }

  @Override
  public Value<Number> evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> materialized = source == null ? Value.nullValue() : source.evaluate(valueRefResolver);
    try {
      if (materialized.isNull()) {
        throw new RuntimeException("Cannot evaluate the expression for null value");
      } else if (materialized.isUndefined()) {
        throw new RuntimeException("Cannot evaluate the expression for undefined value");
      } else if (materialized instanceof StringValue) {
        return (NumericValue) Value.of(((StringValue) materialized).length(), ValueType.INT);
      } else if (materialized instanceof CollectionValue) {
        return (NumericValue) Value.of(((CollectionValue) materialized).count(), ValueType.INT);
      }
    } catch (RuntimeException ex) {
      throw new EvaluationException(ex.getMessage(), PrettyPrintVisitor.print(this));
    }
    log.warn("Can not compute length for {}", materialized);
    return Value.undefined();
  }

  public ValueExpression<?> getSource() {
    return source;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
