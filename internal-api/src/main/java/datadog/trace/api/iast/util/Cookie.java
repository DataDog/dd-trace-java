package datadog.trace.api.iast.util;

public class Cookie {

  private final String cookieName;
  private final boolean secure;
  private final String sameSite;
  private final boolean httpOnly;

  public Cookie(final String cookieName, boolean secure, boolean httpOnly, String sameSite) {
    this.cookieName = cookieName;
    this.secure = secure;
    this.sameSite = sameSite;
    this.httpOnly = httpOnly;
  }

  public String getCookieName() {
    return cookieName;
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
    private boolean secure = false;
    private boolean httpOnly = false;
    private String sameSite = null;

    public Builder(final String name) {
      this.name = name;
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
      return new Cookie(name, secure, httpOnly, sameSite);
    }
  }
}
