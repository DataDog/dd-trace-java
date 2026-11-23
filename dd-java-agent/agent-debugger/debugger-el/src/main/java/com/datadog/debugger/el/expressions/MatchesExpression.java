package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.util.Objects;
import java.util.regex.Pattern;

public class MatchesExpression implements BooleanExpression {
  private final ValueExpression<?> sourceString;
  private final StringValue str;
  private final Pattern pattern;

  public MatchesExpression(ValueExpression<?> sourceString, StringValue str) {
    this.sourceString = sourceString;
    this.str = str;
    pattern = str != null && str.getValue() != null ? Pattern.compile(str.getValue()) : null;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Boolean evaluate(EvalContext evalContext) {
    Value<?> sourceValue =
        sourceString != null ? sourceString.evaluate(evalContext) : Value.nullValue();
    if (sourceValue.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (sourceValue.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    if (pattern != null && sourceValue.getValue() instanceof String) {
      String sourceStr = (String) sourceValue.getValue();
      TimeoutCharSequence timeoutCharSequence =
          new TimeoutCharSequence(evalContext.getTimeoutChecker(), this, sourceStr);
      boolean result = pattern.matcher(timeoutCharSequence).matches();
      ExpressionHelper.checkTimeout(evalContext.getTimeoutChecker(), this);
      return result;
    }
    return Boolean.FALSE;
  }

  public ValueExpression<?> getSourceString() {
    return sourceString;
  }

  public StringValue getStr() {
    return str;
  }

  /** Wraps a String to check the timeout on each char access while the regex is matching. */
  private static class TimeoutCharSequence implements CharSequence {
    private final TimeoutChecker timeoutChecker;
    private final MatchesExpression matchesExpression;
    private final String s;
    private int charAccessCount;

    TimeoutCharSequence(
        TimeoutChecker timeoutChecker, MatchesExpression matchesExpression, String s) {
      this.timeoutChecker = timeoutChecker;
      this.matchesExpression = matchesExpression;
      this.s = s;
    }

    @Override
    public int length() {
      return s.length();
    }

    @Override
    public char charAt(int index) {
      if ((charAccessCount++ & 0xFFF) == 0) {
        //   checks every 4096 char accesses
        ExpressionHelper.checkTimeout(timeoutChecker, matchesExpression);
      }
      return s.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return s.subSequence(start, end);
    }

    @Override
    public String toString() {
      return s;
    }
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    MatchesExpression that = (MatchesExpression) o;
    return Objects.equals(sourceString, that.sourceString) && Objects.equals(str, that.str);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(sourceString, str);
  }
}
