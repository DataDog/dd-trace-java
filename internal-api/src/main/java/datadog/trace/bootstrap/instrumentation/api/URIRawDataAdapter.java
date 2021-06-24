package datadog.trace.bootstrap.instrumentation.api;

public abstract class URIRawDataAdapter extends URIDataAdapterBase {
  private String decodedPath = UNINITIALIZED;
  private String decodedQuery = UNINITIALIZED;
  private String rawPath = UNINITIALIZED;
  private String rawQuery = UNINITIALIZED;

  protected abstract String innerRawPath();

  protected abstract String innerRawQuery();

  @Override
  public final String path() {
    String path = decodedPath;
    if (path != UNINITIALIZED) {
      return path;
    }
    decodedPath = path = URIUtils.decode(rawPath());
    return path;
  }

  @Override
  public final String query() {
    String query = decodedQuery;
    if (query != UNINITIALIZED) {
      return query;
    }
    decodedQuery = query = URIUtils.decode(rawQuery(), hasPlusEncodedSpaces());
    return query;
  }

  @Override
  public final boolean supportsRaw() {
    return true;
  }

  @Override
  public final String rawPath() {
    String path = rawPath;
    if (path == UNINITIALIZED) {
      rawPath = path = innerRawPath();
    }
    return path;
  }

  @Override
  public final String rawQuery() {
    String query = rawQuery;
    if (query == UNINITIALIZED) {
      rawQuery = query = innerRawQuery();
    }
    return query;
  }
}
