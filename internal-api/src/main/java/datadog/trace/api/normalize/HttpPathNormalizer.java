package datadog.trace.api.normalize;

public abstract class HttpPathNormalizer {
  public final String normalize(String path) {
    return normalize(path, false);
  }

  public abstract String normalize(String path, boolean encoded);
}
