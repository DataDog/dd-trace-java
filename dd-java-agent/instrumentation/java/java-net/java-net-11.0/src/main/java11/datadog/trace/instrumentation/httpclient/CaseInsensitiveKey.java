package datadog.trace.instrumentation.httpclient;

import java.util.Locale;
import java.util.Objects;

/**
 * A class usable as key in a Set or Map that matches case-insensitively but still contains the
 * original value.
 */
public final class CaseInsensitiveKey {
  private final String value;
  private final String lowercase;

  public CaseInsensitiveKey(final String value) {
    this.value = value;
    this.lowercase = value != null ? value.toLowerCase(Locale.ROOT) : null;
  }

  public String getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CaseInsensitiveKey)) {
      return false;
    }
    CaseInsensitiveKey that = (CaseInsensitiveKey) o;
    return Objects.equals(lowercase, that.lowercase);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(lowercase);
  }
}
