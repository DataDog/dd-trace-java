package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.ExpressionHelper.checkTimeout;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.EvaluationTimeOutException;
import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import java.util.Objects;

/**
 * Takes two {@linkplain ValueExpression} instances and compares them using the given {@link
 * ComparisonOperator operator}.
 */
public class ComparisonExpression implements BooleanExpression {
  private final ValueExpression<?> left;
  private final ValueExpression<?> right;
  private final ComparisonOperator operator;

  public ComparisonExpression(
      ValueExpression<?> left, ValueExpression<?> right, ComparisonOperator operator) {
    this.left = left == null ? ValueExpression.NULL : left;
    this.right = right == null ? ValueRefExpression.NULL : right;
    this.operator = operator;
  }

  @Override
  public Boolean evaluate(EvalContext evalContext) {
    Value<?> leftValue = left.evaluate(evalContext);
    if (leftValue.isUndefined()) {
      return Boolean.FALSE;
    }
    Value<?> rightValue = right.evaluate(evalContext);
    if (rightValue.isUndefined()) {
      return Boolean.FALSE;
    }
    try {
      boolean result = operator.apply(leftValue, rightValue);
      checkTimeout(evalContext.getTimeoutChecker(), this);
      return result;
    } catch (EvaluationTimeOutException e) {
      throw new EvaluationTimeOutException(e.getMessage(), PrettyPrintVisitor.print(this));
    } catch (EvaluationException e) {
      throw new EvaluationException(e.getMessage(), PrettyPrintVisitor.print(this));
    }
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getLeft() {
    return left;
  }

  public ValueExpression<?> getRight() {
    return right;
  }

  public ComparisonOperator getOperator() {
    return operator;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    ComparisonExpression that = (ComparisonExpression) o;
    return Objects.equals(left, that.left)
        && Objects.equals(right, that.right)
        && operator == that.operator;
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(left, right, operator);
  }
}
