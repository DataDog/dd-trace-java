package datadog.opentelemetry.shim;

import java.util.Objects;
import javax.annotation.Nullable;

/** Instrumentation scopes have a mandatory name, optional version, and optional schema URL. */
public final class OtelInstrumentationScope {

  private final String scopeName;
  @Nullable private final String scopeVersion;
  @Nullable private final String schemaUrl;

  public OtelInstrumentationScope(
      String scopeName, @Nullable String scopeVersion, @Nullable String schemaUrl) {
    this.scopeName = scopeName;
    this.scopeVersion = scopeVersion;
    this.schemaUrl = schemaUrl;
  }

  public String getName() {
    return scopeName;
  }

  @Nullable
  public String getVersion() {
    return scopeVersion;
  }

  @Nullable
  public String getSchemaUrl() {
    return schemaUrl;
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
