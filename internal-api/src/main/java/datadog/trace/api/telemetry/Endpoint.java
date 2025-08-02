package datadog.trace.api.telemetry;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Endpoint {
  private boolean fist;
  private String type;
  private String method;
  private String path;
  private String operation;
  private String resource;
  private List<String> requestBodyType;
  private List<String> responseBodyType;
  private List<Integer> responseCode;
  private List<String> authentication;
  private Map<String, String> metadata;

  public boolean isFirst() {
    return fist;
  }

  public Endpoint first(boolean first) {
    this.fist = first;
    return this;
  }

  public String getType() {
    return type;
  }

  public Endpoint type(final String type) {
    this.type = type;
    return this;
  }

  public String getMethod() {
    return method;
  }

  public Endpoint method(final String method) {
    this.method = method;
    return this;
  }

  public String getPath() {
    return path;
  }

  public Endpoint path(final String path) {
    this.path = path;
    return this;
  }

  public String getOperation() {
    return operation;
  }

  public Endpoint operation(final String operation) {
    this.operation = operation;
    return this;
  }

  public String getResource() {
    return resource;
  }

  public Endpoint resource(final String resource) {
    this.resource = resource;
    return this;
  }

  public List<String> getRequestBodyType() {
    return requestBodyType;
  }

  public Endpoint requestBodyType(final List<String> requestBodyType) {
    this.requestBodyType = requestBodyType;
    return this;
  }

  public List<String> getResponseBodyType() {
    return responseBodyType;
  }

  public Endpoint responseBodyType(final List<String> responseBodyType) {
    this.responseBodyType = responseBodyType;
    return this;
  }

  public List<Integer> getResponseCode() {
    return responseCode;
  }

  public Endpoint responseCode(final List<Integer> responseCode) {
    this.responseCode = responseCode;
    return this;
  }

  public List<String> getAuthentication() {
    return authentication;
  }

  public Endpoint authentication(final List<String> authentication) {
    this.authentication = authentication;
    return this;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  public Endpoint metadata(final Map<String, String> metadata) {
    this.metadata = metadata;
    return this;
  }

  public interface Operation {
    String HTTP_REQUEST = "http.request";
  }

  public interface Type {
    String REST = "REST";
  }

  public interface Method {
    String CONNECT = "CONNECT";
    String HEAD = "HEAD";
    String GET = "GET";
    String POST = "POST";
    String PUT = "PUT";
    String PATCH = "PATCH";
    String OPTIONS = "OPTIONS";
    String DELETE = "DELETE";
    String TRACE = "TRACE";
    String ALL = "*";

    Set<String> METHODS =
        new HashSet<>(Arrays.asList(CONNECT, HEAD, GET, POST, PUT, PATCH, OPTIONS, DELETE, TRACE));

    static <E extends Enum<E>, C extends Collection<E>> List<String> parseMethods(final C methods) {
      if (methods == null || methods.isEmpty()) {
        return singletonList(ALL);
      }
      final List<String> result = new ArrayList<>(methods.size());
      for (E value : methods) {
        final String methodName = value.name().toUpperCase();
        if (METHODS.contains(value.name())) {
          result.add(methodName);
        }
      }
      return result;
    }
  }
}
