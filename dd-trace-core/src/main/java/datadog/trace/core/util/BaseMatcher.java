package datadog.trace.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;

public abstract class BaseMatcher implements Matcher {
  public abstract boolean matches(String str);

  public abstract boolean matches(CharSequence charSeq);

  @Override
  public final boolean matches(Object value) {
    if (value instanceof String) {
      return matches((String) value);
    } else if (value instanceof CharSequence) {
      return matches((CharSequence) value);
    } else if (value instanceof Boolean) {
      return matches((boolean) value);
    } else if (value instanceof Byte) {
      return matches((byte) value);
    } else if (value instanceof Short) {
      return matches((short) value);
    } else if (value instanceof Integer) {
      return matches((int) value);
    } else if (value instanceof Long) {
      return matches((long) value);
    } else if (value instanceof BigInteger) {
      return matches((BigInteger) value);
    } else if (value instanceof Float) {
      return matches((float) value);
    } else if (value instanceof Double) {
      return matches((double) value);
    } else if (value instanceof BigDecimal) {
      return matches((BigDecimal) value);
    } else {
      return false;
    }
  }

  @Override
  public boolean matches(boolean value) {
    // DQH - TODO: Also 0 or 1???
    return matches(value ? "true" : "false");
  }

  @Override
  public boolean matches(byte value) {
    return matches((int) value);
  }

  @Override
  public boolean matches(short value) {
    return matches((int) value);
  }

  @Override
  public boolean matches(int value) {
    return matches(Integer.toString(value));
  }

  @Override
  public boolean matches(long value) {
    return matches(Long.toString(value));
  }

  @Override
  public boolean matches(BigInteger value) {
    return matches(value.toString());
  }

  @Override
  public boolean matches(double value) {
    if (Math.floor(value) == value) {
      return matches((int) value);
    } else {
      return false;
    }
  }

  @Override
  public boolean matches(float value) {
    if (Math.floor(value) == value) {
      return matches((int) value);
    } else {
      return false;
    }
  }

  @Override
  public boolean matches(BigDecimal value) {
    String str = value.toPlainString();
    if (str.indexOf('.') == -1) {
      return matches(str);
    } else {
      return false;
    }
  }
}
