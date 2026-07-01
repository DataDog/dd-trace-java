package datadog.trace.bootstrap.otel.common;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Objects;
import javax.annotation.Nullable;

/** Instrumentation scopes have a mandatory name, optional version, and optional schema URL. */
public final class OtelInstrumentationScope implements Comparable<OtelInstrumentationScope> {

  private final UTF8BytesString scopeName;
  @Nullable private final UTF8BytesString scopeVersion;
  @Nullable private final UTF8BytesString schemaUrl;

  public OtelInstrumentationScope(
      String scopeName, @Nullable String scopeVersion, @Nullable String schemaUrl) {
    this.scopeName = UTF8BytesString.create(scopeName);
    this.scopeVersion = UTF8BytesString.create(scopeVersion);
    this.schemaUrl = UTF8BytesString.create(schemaUrl);
  }

  public UTF8BytesString getName() {
    return scopeName;
  }

  @Nullable
  public UTF8BytesString getVersion() {
    return scopeVersion;
  }

  @Nullable
  public UTF8BytesString getSchemaUrl() {
    return schemaUrl;
  }

  @Override
  public int compareTo(OtelInstrumentationScope that) {
    int cmp = scopeName.toString().compareTo(that.scopeName.toString());
    if (cmp != 0) {
      return cmp;
    }
    if (scopeVersion != that.scopeVersion) {
      if (scopeVersion == null) {
        return -1;
      } else if (that.scopeVersion == null) {
        return 1;
      }
      cmp = scopeVersion.toString().compareTo(that.scopeVersion.toString());
      if (cmp != 0) {
        return cmp;
      }
    }
    if (schemaUrl != that.schemaUrl) {
      if (schemaUrl == null) {
        return -1;
      } else if (that.schemaUrl == null) {
        return 1;
      }
      return schemaUrl.toString().compareTo(that.schemaUrl.toString());
    }
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OtelInstrumentationScope)) {
      return false;
    }

    OtelInstrumentationScope that = (OtelInstrumentationScope) o;
    return scopeName.equals(that.scopeName)
        && Objects.equals(scopeVersion, that.scopeVersion)
        && Objects.equals(schemaUrl, that.schemaUrl);
  }

  @Override
  public int hashCode() {
    int result = scopeName.hashCode();
    result = 31 * result + Objects.hashCode(scopeVersion);
    result = 31 * result + Objects.hashCode(schemaUrl);
    return result;
  }

  @Override
  public String toString() {
    // use same property names as OTel in toString
    return "OtelInstrumentationScope{"
        + "name='"
        + scopeName
        + (scopeVersion != null ? "', version='" + scopeVersion : "")
        + (schemaUrl != null ? "', schemaUrl='" + schemaUrl : "")
        + "'}";
  }
}
