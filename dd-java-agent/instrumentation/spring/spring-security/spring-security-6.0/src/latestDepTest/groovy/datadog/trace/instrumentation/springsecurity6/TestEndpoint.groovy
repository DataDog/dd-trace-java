package datadog.trace.instrumentation.springsecurity6

enum TestEndpoint {
  LOGIN("login", 302, ""),
  REGISTER("register", 200, ""),
  NOT_FOUND("not-found", 404, "not found"),
  UNKNOWN("", 451, null),
  CUSTOM("custom", 302, ""),
  SUCCESS("success", 200, ""),

  private final String path
  private final String rawPath
  final String query
  final String rawQuery
  final String fragment
  final int status
  final String body

  TestEndpoint(String uri, int status, String body) {
    def uriObj = URI.create(uri)
    this.path = uriObj.path
    this.rawPath = uriObj.rawPath
    this.query = uriObj.query
    this.rawQuery = uriObj.rawQuery
    this.fragment = uriObj.fragment
    this.status = status
    this.body = body
  }

  String getPath() {
    return "/$path"
  }

  String relativePath() {
    return path
  }

  String getRawPath() {
    return "/$rawPath"
  }

  String relativeRawPath() {
    return rawPath
  }

  URI resolve(URI address) {
    return address.resolve(relativeRawPath())
  }
}
