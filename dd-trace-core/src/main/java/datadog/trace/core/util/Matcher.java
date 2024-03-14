package datadog.trace.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;

public interface Matcher {
  boolean matches(String str);

  boolean matches(CharSequence charSeq);

  boolean matches(Object value);

  boolean matches(boolean value);

  boolean matches(byte value);

  boolean matches(short value);

  boolean matches(int value);

  boolean matches(long value);

  boolean matches(BigInteger value);

  boolean matches(double value);

  boolean matches(float value);

  boolean matches(BigDecimal value);
}
