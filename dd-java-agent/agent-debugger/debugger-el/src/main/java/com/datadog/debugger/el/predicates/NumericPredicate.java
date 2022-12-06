package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Value;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A common superclass for predicates operating on numbers. */
public abstract class NumericPredicate extends ValuePredicate {
  private static final Logger log = LoggerFactory.getLogger(NumericPredicate.class);

  protected NumericPredicate(Value<?> left, Value<?> right, Operator operator) {
    super(validate(left), validate(right), operator);
  }

  static Value<?> validate(Value<?> value) {
    if (!isUnset(value) && !(value.getValue() instanceof Number)) {
      log.warn("Value {} is not a number", value);
      throw new IllegalArgumentException(value + " is not a number");
    }
    return value;
  }

  protected static int compare(Value<?> left, Value<?> right) {
    if (isUnset(left) && isUnset(right)) {
      return 0;
    } else if (isUnset(left) && !isUnset(right)) {
      return -1;
    } else if (!isUnset(left) && isUnset(right)) {
      return 1;
    }
    return compare((Number) left.getValue(), (Number) right.getValue());
  }

  private static boolean isUnset(Value<?> value) {
    return value.isNull() || value.isUndefined();
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
