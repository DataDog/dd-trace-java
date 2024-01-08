package datadog.trace.core.util;

public interface Matcher {
  boolean matches(String str);

  boolean matches(CharSequence charSeq);
}
