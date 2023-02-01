package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.NumericValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public enum ComparisonOperator {
  EQ("==") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        Number leftNumber = (Number) left.getValue();
        Number rightNumber = (Number) right.getValue();
        if (isNan(leftNumber, rightNumber)) {
          return Boolean.FALSE;
        }
        return compare(leftNumber, rightNumber) == 0;
      }
      if (left.getValue().getClass() == right.getValue().getClass()) {
        return Objects.equals(left.getValue(), right.getValue());
      }
      return Boolean.FALSE;
    }
  },
  GE(">=") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        Number leftNumber = (Number) left.getValue();
        Number rightNumber = (Number) right.getValue();
        if (isNan(leftNumber, rightNumber)) {
          return Boolean.FALSE;
        }
        return compare(leftNumber, rightNumber) >= 0;
      }
      return Boolean.FALSE;
    }
  },
  GT(">") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        Number leftNumber = (Number) left.getValue();
        Number rightNumber = (Number) right.getValue();
        if (isNan(leftNumber, rightNumber)) {
          return Boolean.FALSE;
        }
        return compare(leftNumber, rightNumber) > 0;
      }
      return Boolean.FALSE;
    }
  },
  LE("<=") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        Number leftNumber = (Number) left.getValue();
        Number rightNumber = (Number) right.getValue();
        if (isNan(leftNumber, rightNumber)) {
          return Boolean.FALSE;
        }
        return compare(leftNumber, rightNumber) <= 0;
      }
      return Boolean.FALSE;
    }
  },
  LT("<") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        Number leftNumber = (Number) left.getValue();
        Number rightNumber = (Number) right.getValue();
        if (isNan(leftNumber, rightNumber)) {
          return Boolean.FALSE;
        }
        return compare(leftNumber, rightNumber) < 0;
      }
      return Boolean.FALSE;
    }
  };

  private String symbol;

  ComparisonOperator(String symbol) {
    this.symbol = symbol;
  }

  public abstract Boolean apply(Value<?> left, Value<?> right);

  public String prettyPrint() {
    return symbol;
  }

  protected boolean isNan(Number... numbers) {
    boolean result = false;
    for (Number number : numbers) {
      result |= number instanceof Double && Double.isNaN(number.doubleValue());
    }
    return result;
  }

  protected static int compare(Number left, Number right) {
    if (isSpecial(left) || isSpecial(right)) {
      return Double.compare(left.doubleValue(), right.doubleValue());
    } else {
      return toBigDecimal(left).compareTo(toBigDecimal(right));
    }
  }

  private static boolean isSpecial(Number x) {
    boolean specialDouble = x instanceof Double && Double.isInfinite((Double) x);
    boolean specialFloat = x instanceof Float && Float.isInfinite((Float) x);
    return specialDouble || specialFloat;
  }

  private static BigDecimal toBigDecimal(Number number) throws NumberFormatException {
    if (number instanceof BigDecimal) return (BigDecimal) number;
    if (number instanceof BigInteger) return new BigDecimal((BigInteger) number);
    if (number instanceof Byte
        || number instanceof Short
        || number instanceof Integer
        || number instanceof Long) return BigDecimal.valueOf(number.longValue());
    if (number instanceof Float || number instanceof Double)
      return BigDecimal.valueOf(number.doubleValue());

    return new BigDecimal(number.toString());
  }
}
