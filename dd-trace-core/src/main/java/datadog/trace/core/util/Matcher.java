package datadog.trace.core.util;

public interface Matcher {
  public boolean matches(String str);

  public boolean matches(CharSequence charSeq);
}
