package datadog.trace.instrumentation.springsecurity5

enum TestEndpoint {
    SUCCESS("success", 200, "success"),
    CREATED("created", 201, "created"),
    CREATED_IS("created_input_stream", 201, "created"),
    BODY_URLENCODED("body-urlencoded?ignore=pair", 200, '[a:[x]]'),
    BODY_MULTIPART("body-multipart?ignore=pair", 200, '[a:[x]]'),
    BODY_JSON("body-json", 200, '{"a":"x"}'),
    REDIRECT("redirect", 302, "/redirected"),
    FORWARDED("forwarded", 200, "1.2.3.4"),
    ERROR("error-status", 500, "controller error"), // "error" is a special path for some frameworks
    EXCEPTION("exception", 500, "controller exception"),
    CUSTOM_EXCEPTION("custom-exception", 510, "custom exception"), // exception thrown with custom error
    NOT_FOUND("not-found", 404, "not found"),
    NOT_HERE("not-here", 404, "not here"), // Explicitly returned 404 from a valid controller

    TIMEOUT("timeout", 500, null),
    TIMEOUT_ERROR("timeout_error", 500, null),

    USER_BLOCK("user-block", 403, null),

    QUERY_PARAM("query?some=query", 200, "some=query"),
    QUERY_ENCODED_BOTH("encoded%20path%20query?some=is%20both", 200, "some=is both"),
    QUERY_ENCODED_QUERY("encoded_query?some=is%20query", 200, "some=is query"),
    PATH_PARAM("path/123/param", 200, "123"),
    MATRIX_PARAM("matrix/a=x,y;a=z", 200, '[a:[x, y, z]]'),
    AUTH_REQUIRED("authRequired", 200, null),
    LOGIN("login", 302, ""),
    REGISTER("register", 200, ""),
    UNKNOWN("", 451, null), // This needs to have a valid status code

    private final String path
    private final String rawPath
    final String query
    final String rawQuery
    final String fragment
    final int status
    final String body
    final Boolean errored
    final Boolean throwsException
    final boolean hasPathParam

    TestEndpoint(String uri, int status, String body) {
        def uriObj = URI.create(uri)
        this.path = uriObj.path
        this.rawPath = uriObj.rawPath
        this.query = uriObj.query
        this.rawQuery = uriObj.rawQuery
        this.fragment = uriObj.fragment
        this.status = status
        this.body = body
        this.errored = status >= 500 || name().contains("ERROR")
        this.throwsException = name().contains("EXCEPTION")
        this.hasPathParam = body == "123"
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
        // must be relative path to allow for servlet context
        return address.resolve(relativeRawPath())
    }

    String bodyForQuery(String queryString) {
        if (queryString.equals(query) || queryString.equals(rawQuery)) {
            return body
        }
        return "non matching query string '$queryString'"
    }

    static {
        assert values().length == values().collect { it.path }.toSet().size(): "paths should be unique"
    }

    private static final Map<String, TestEndpoint> PATH_MAP = {
        Map<String, TestEndpoint> map = values().collectEntries { [it.path, it]}
        map.putAll(values().collectEntries { [it.rawPath, it]})
        map
    }.call()

    // Will match both decoded and encoded path
    static TestEndpoint forPath(String path) {
        def endpoint = PATH_MAP.get(path)
        return endpoint != null ? endpoint : UNKNOWN
    }
}