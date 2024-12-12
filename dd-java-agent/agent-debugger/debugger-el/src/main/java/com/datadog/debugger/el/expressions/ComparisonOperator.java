package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public enum ComparisonOperator {
  EQ("==") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (left instanceof NumericValue && right instanceof NumericValue) {
        Number leftNumber = ((NumericValue) left).getWidenValue();
        Number rightNumber = ((NumericValue) right).getWidenValue();
        if (isNan(leftNumber, rightNumber)) {
          return Boolean.FALSE;
        }
        return compare(leftNumber, rightNumber) == 0;
      }
      if (left.getValue() instanceof Enum || right.getValue() instanceof Enum) {
        return applyEqualityForEnum(left, right);
      }
      if (left.getValue().getClass() == right.getValue().getClass()) {
        return Objects.equals(left.getValue(), right.getValue());
      }
      return Boolean.FALSE;
    }

    private Boolean applyEqualityForEnum(Value<?> left, Value<?> right) {
      if (left.getValue() instanceof Enum && right instanceof StringValue) {
        return doApplyEqualityForEnum(left, right);
      }
      if (left instanceof StringValue && right.getValue() instanceof Enum) {
        return doApplyEqualityForEnum(right, left);
      }
      throw new EvaluationException(
          "Equality operator is not supported for the given types: "
              + left.getValue().getClass().getName()
              + " and "
              + right.getValue().getClass().getName(),
          null);
    }

    private Boolean doApplyEqualityForEnum(Value<?> enumExpr, Value<?> enumValueExpr) {
      Enum<?> enumValue = (Enum<?>) enumExpr.getValue();
      String enumValueStr = (String) enumValueExpr.getValue();
      Class<? extends Enum> enumClass = enumValue.getClass();
      Enum[] enumConstants = enumClass.getEnumConstants();
      for (Enum<?> enumConstant : enumConstants) {
        // Check if string constant as value expression matches for enum constant
        // the endsWith allow to match either:
        // - the full enum constant name (com.datadog.debugger.MyEnum.ONE)
        // - the simple name with enum class name (MyEnum.ONE)
        // - the simple name (ONE)
        // The second check against enumValue is to ensure the instance filtered based on the
        // name is still correct because the name can partially match (CLOSE in OPENCLOSE)
        // with an enum defined like (OPEN, CLOSE, OPENCLOSE)
        if (enumValueStr.endsWith(enumConstant.name())) {
          if (enumValue.equals(enumConstant)) {
            return Boolean.TRUE;
          }
        }
      }
      return Boolean.FALSE;
    }
  },
  GE(">=") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      Integer result = compare(left, right);
      if (result == null) {
        return Boolean.FALSE;
      }
      return result >= 0;
    }
  },
  GT(">") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      Integer result = compare(left, right);
      if (result == null) {
        return Boolean.FALSE;
      }
      return result > 0;
    }
  },
  LE("<=") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      Integer result = compare(left, right);
      if (result == null) {
        return Boolean.FALSE;
      }
      return result <= 0;
    }
  },
  LT("<") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      Integer result = compare(left, right);
      if (result == null) {
        return Boolean.FALSE;
      }
      return result < 0;
    }
  },
  INSTANCEOF("instanceof") {
    @Override
    public Boolean apply(Value<?> left, Value<?> right) {
      if (right.getValue() instanceof String) {
        String typeStr = (String) right.getValue();
        Class<?> clazz;
        try {
          clazz = Class.forName(typeStr, false, left.getValue().getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
          throw new EvaluationException("Class not found: " + typeStr, null);
        }
        return clazz.isInstance(left.getValue());
      }
      throw new EvaluationException(
          "Right operand of instanceof operator must be a string literal", null);
    }
  };

  private final String symbol;

  ComparisonOperator(String symbol) {
    this.symbol = symbol;
  }

  public abstract Boolean apply(Value<?> left, Value<?> right);

  public String getSymbol() {
    return symbol;
  }

  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  protected static boolean isNan(Number... numbers) {
    boolean result = false;
    for (Number number : numbers) {
      result |= number instanceof Double && Double.isNaN(number.doubleValue());
    }
    return result;
  }

  protected static Integer compare(Value<?> left, Value<?> right) {
    if (left instanceof NumericValue && right instanceof NumericValue) {
      Number leftNumber = ((NumericValue) left).getWidenValue();
      Number rightNumber = ((NumericValue) right).getWidenValue();
      if (isNan(leftNumber, rightNumber)) {
        return null;
      }
      return compare(leftNumber, rightNumber);
    }
    if (left instanceof StringValue && right instanceof StringValue) {
      return ((StringValue) left).getValue().compareTo(((StringValue) right).getValue());
    }
    return null;
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
