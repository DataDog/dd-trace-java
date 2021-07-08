package datadog.trace.api.http;

public abstract class PathNormalizer {
  public final String normalize(String path) {
    return normalize(path, false);
  }

  public abstract String normalize(String path, boolean encoded);
}
