package datadog.trace.api.normalize;

abstract class HttpPathNormalizer {
  public final String normalize(String path) {
    return normalize(path, false);
  }

  public abstract String normalize(String path, boolean encoded);

  /**
   * Normalizes the given HTTP path with optional host context. Implementations that support
   * host-aware normalization can use the host to disambiguate identical paths across hosts.
   *
   * @param host the target host/domain (may be {@code null} or empty)
   * @param path the request path
   * @param encoded whether the path is URL-encoded
   * @return the normalized resource name path, or {@code null} if no mapping matched
   */
  public String normalize(String host, String path, boolean encoded) {
    return normalize(path, encoded);
  }
}
