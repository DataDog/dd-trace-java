package datadog.trace.bootstrap.instrumentation.api;

public interface URIDataAdapter {

  String scheme();

  String host();

  int port();

  /** @return the raw, unencoded path */
  String path();

  String fragment();

  /** @return the raw, unencoded query string */
  String query();
}
