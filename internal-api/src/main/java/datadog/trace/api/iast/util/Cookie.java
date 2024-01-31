package datadog.trace.api.iast.util;

public class Cookie {

  private final String cookieName;
  private final String cookieValue;
  private final boolean secure;
  private final String sameSite;
  private final boolean httpOnly;

  public Cookie(
      final String cookieName,
      final String cookieValue,
      boolean secure,
      boolean httpOnly,
      String sameSite) {
    this.cookieName = cookieName;
    this.cookieValue = cookieValue;
    this.secure = secure;
    this.sameSite = sameSite;
    this.httpOnly = httpOnly;
  }

  public String getCookieName() {
    return cookieName;
  }

  public String getCookieValue() {
    return cookieValue;
  }

  public boolean isSecure() {
    return secure;
  }

  public String getSameSite() {
    return sameSite;
  }

  public boolean isHttpOnly() {
    return httpOnly;
  }

  public static Builder named(final String name) {
    return new Builder(name);
  }

  public static class Builder {
    private final String name;
    private String value;
    private boolean secure = false;
    private boolean httpOnly = false;
    private String sameSite = null;

    public Builder(final String name) {
      this.name = name;
    }

    public Builder value(final String value) {
      this.value = value;
      return this;
    }

    public Builder secure(final boolean secure) {
      this.secure = secure;
      return this;
    }

    public Builder httpOnly(final boolean httpOnly) {
      this.httpOnly = httpOnly;
      return this;
    }

    public Builder sameSite(final String sameSite) {
      this.sameSite = sameSite;
      return this;
    }

    public Cookie build() {
      return new Cookie(name, value, secure, httpOnly, sameSite);
    }
  }
}
