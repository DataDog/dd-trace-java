package datadog.trace.bootstrap.instrumentation.api;

public interface URIDataAdapter {

  String scheme();

  String host();

  int port();

  String path();

  String fragment();

  String query();
}
