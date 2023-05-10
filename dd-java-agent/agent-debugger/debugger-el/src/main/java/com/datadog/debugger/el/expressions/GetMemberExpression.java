package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.util.Objects;

public class GetMemberExpression implements ValueExpression<Value<?>> {
  private final ValueExpression<?> target;
  private final String memberName;

  public GetMemberExpression(ValueExpression<?> target, String memberName) {
    this.target = target;
    this.memberName = memberName;
  }

  @Override
  public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> targetValue = target.evaluate(valueRefResolver);
    if (targetValue == Value.undefined()) {
      return targetValue;
    }
    try {
      return Value.of(valueRefResolver.getMember(targetValue.getValue(), memberName));
    } catch (RuntimeException ex) {
      throw new EvaluationException(ex.getMessage(), memberName, ex);
    }
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GetMemberExpression that = (GetMemberExpression) o;
    return Objects.equals(target, that.target) && Objects.equals(memberName, that.memberName);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(target, memberName);
  }

  @Generated
  @Override
  public String toString() {
    return "GetMemberExpression{" + "target=" + target + ", memberName='" + memberName + '\'' + '}';
  }

  public ValueExpression<?> getTarget() {
    return target;
  }

  public String getMemberName() {
    return memberName;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
