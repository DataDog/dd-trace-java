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
      if (left.getValue().getClass() == right.getValue().getClass()) {
        return Objects.equals(left.getValue(), right.getValue());
      } else if (left instanceof NumericValue && right instanceof NumericValue) {
        return compare(left, right) == 0;
      } else {
        return Boolean.FALSE;
      }
    }
  },
  GE(">=") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        return compare(left, right) >= 0;
      }
      return Boolean.FALSE;
    }
  },
  GT(">") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        return compare(left, right) > 0;
      }
      return Boolean.FALSE;
    }
  },
  LE("<=") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        return compare(left, right) <= 0;
      }
      return Boolean.FALSE;
    }
  },
  LT("<") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        return compare(left, right) < 0;
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

  protected static int compare(Value<?> left, Value<?> right) {
    return compare((Number) left.getValue(), (Number) right.getValue());
  }

  private static int compare(Number left, Number right) {
    if (isSpecial(left) || isSpecial(right)) {
      return Double.compare(left.doubleValue(), right.doubleValue());
    } else {
      return toBigDecimal(left).compareTo(toBigDecimal(right));
    }
  }

  private static boolean isSpecial(Number x) {
    boolean specialDouble =
        x instanceof Double && (Double.isNaN((Double) x) || Double.isInfinite((Double) x));
    boolean specialFloat =
        x instanceof Float && (Float.isNaN((Float) x) || Float.isInfinite((Float) x));
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
