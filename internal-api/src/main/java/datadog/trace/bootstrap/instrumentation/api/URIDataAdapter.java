package datadog.trace.bootstrap.instrumentation.api;

public interface URIDataAdapter {
  /** The scheme of this URI. Can never be encoded. */
  String scheme();

  /** The host of this URI. Can never be encoded. */
  String host();

  /** The port number of this URI. */
  int port();

  /** The decoded path of this URI. */
  String path();

  /** The decoded fragment of this URI. */
  String fragment();

  /** The decoded query string of this URI. */
  String query();

  /** Does this adapter support raw encoded access? */
  boolean supportsRaw();

  /** The raw path of this URI. */
  String rawPath();

  /** Does the raw query string have '+' encoded spaces? */
  boolean hasPlusEncodedSpaces();

  /** The raw query string of this URI. */
  String rawQuery();

  /** The raw path(?query) of this URI. */
  String raw();

  /**
   * The URI is a valid one or not (looks malformed)
   *
   * @return
   */
  boolean isValid();
}
