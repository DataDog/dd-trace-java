package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nonnull;

public class UnparseableURIDataAdapter extends URIDataAdapterBase {
  private final String rawUri;

  public UnparseableURIDataAdapter(@Nonnull String rawUri) {
    this.rawUri = rawUri;
  }

  @Override
  public boolean isValid() {
    return false;
  }

  @Override
  public String scheme() {
    return null;
  }

  @Override
  public String host() {
    return null;
  }

  @Override
  public int port() {
    return 0;
  }

  @Override
  public String path() {
    return null;
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  public String query() {
    return null;
  }

  @Override
  public boolean supportsRaw() {
    return true;
  }

  @Override
  public String rawPath() {
    return null;
  }

  @Override
  public String rawQuery() {
    return null;
  }

  @Override
  public String raw() {
    return rawUri;
  }
}
