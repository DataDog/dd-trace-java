package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface KnownAddresses {
  /** Body, as parsed by the server/framework. */
  Address<Object> REQUEST_BODY_OBJECT = new Address<>("server.request.body");

  /** The first characters of the raw HTTP body */
  Address<CharSequence> REQUEST_BODY_RAW = new Address<>("server.request.body.raw");

  /** The scheme used (e.g. http/https) */
  Address<String> REQUEST_SCHEME = new Address<>("_server.request.scheme");

  /** The unparsed request uri, incl. the query string. */
  Address<String> REQUEST_URI_RAW = new Address<>("server.request.uri.raw");

  /** The socket IP address of the client. */
  Address<String> REQUEST_CLIENT_IP = new Address<>("server.request.client_ip");

  /** The peer port */
  Address<Integer> REQUEST_CLIENT_PORT = new Address<>("_server.request.client_port");

  /** The inferred IP address of the client */
  Address<String> REQUEST_INFERRED_CLIENT_IP = new Address<>("http.client_ip");

  /** The verb of the HTTP request. */
  Address<String> REQUEST_METHOD = new Address<>("server.request.method");

  /**
   * incoming url parameters as parsed by the framework (e.g /foo/:id gives a id param that is not
   * part of the query
   */
  Address<Map<String, ?>> REQUEST_PATH_PARAMS = new Address<>("server.request.path_params");

  /** Cookies as parsed by the server */
  Address<Map<String, ? extends Collection<String>>> REQUEST_COOKIES =
      new Address<>("server.request.cookies");

  /** Same as server transport related field. */
  Address<String> REQUEST_TRANSPORT = new Address<>("server.request.transport");

  /** status code of HTTP response */
  Address<String> RESPONSE_STATUS = new Address<>("server.response.status");

  /** First chars of HTTP response body */
  Address<String> RESPONSE_BODY_RAW = new Address<>("server.response.body.raw");

  /** Reponse headers excluding cookies */
  Address<Map<String, List<String>>> RESPONSE_HEADERS_NO_COOKIES =
      new Address<>("server.response.headers.no_cookies");

  /**
   * Contains a list of form fields that were used for file upload. Available only on inspected
   * multipart/form-data requests
   */
  Address<List<String>> REQUEST_FILES_FIELD_NAMES =
      new Address<>("server.request.body.files_field_names");

  /**
   * Contains the list of every uploaded filename from this request. The filename is the one from
   * the user's filesystem
   */
  Address<List<String>> REQUEST_FILES_FILENAMES = new Address<>("server.request.body.filenames");

  /**
   * Contains the total size of the files transported in request body. Available only on inspected
   * multipart/form-data requests.
   */
  Address<Long> REQUEST_COMBINED_FILE_SIZE =
      new Address<>("server.request.body.combined_file_size");

  /**
   * The parsed query string.
   *
   * <p><b>WILLFUL spec violation</b>. Spec says: This is the URL querystring as parsed by the
   * framework (into a key-value-like object, possibly with multiple values)
   *
   * <p>In servlet, it's not possible to get the servlet parsed parameters, because that would imply
   * consuming the post data as well. So we fetch the query string and parse it ourselves. This may
   * not correspond to the same parsing the framework will do.
   *
   * <p>TODO: It will be possible to satisfy the spec with other servers. So this parsing should
   * then be moved to the Servlet HttpContext impl.
   */
  Address<Map<String, List<String>>> REQUEST_QUERY = new Address<>("server.request.query");

  /** Headers with the cookie fields excluded. */
  Address<CaseInsensitiveMap<List<String>>> HEADERS_NO_COOKIES =
      new Address<>("server.request.headers.no_cookies");

  Address<Object> GRPC_SERVER_REQUEST_MESSAGE = new Address<>("grpc.server.request.message");

  // XXX: Not really used yet, but it's a known address and we should not treat it as unknown.
  Address<Object> GRPC_SERVER_REQUEST_METADATA = new Address<>("grpc.server.request.metadata");

  Address<String> USER_ID = new Address<>("usr.id");

  static Address<?> forName(String name) {
    switch (name) {
      case "server.request.body":
        return REQUEST_BODY_OBJECT;
      case "server.request.body.raw":
        return REQUEST_BODY_RAW;
      case "_server.request.scheme":
        return REQUEST_SCHEME;
      case "server.request.uri.raw":
        return REQUEST_URI_RAW;
      case "server.request.client_ip":
        return REQUEST_CLIENT_IP;
      case "_server.request.client_port":
        return REQUEST_CLIENT_PORT;
      case "http.client_ip":
        return REQUEST_INFERRED_CLIENT_IP;
      case "server.request.method":
        return REQUEST_METHOD;
      case "server.request.path_params":
        return REQUEST_PATH_PARAMS;
      case "server.request.cookies":
        return REQUEST_COOKIES;
      case "server.request.transport":
        return REQUEST_TRANSPORT;
      case "server.response.status":
        return RESPONSE_STATUS;
      case "server.response.body.raw":
        return RESPONSE_BODY_RAW;
      case "server.response.headers.no_cookies":
        return RESPONSE_HEADERS_NO_COOKIES;
      case "server.request.body.files_field_names":
        return REQUEST_FILES_FIELD_NAMES;
      case "server.request.body.filenames":
        return REQUEST_FILES_FILENAMES;
      case "server.request.body.combined_file_size":
        return REQUEST_COMBINED_FILE_SIZE;
      case "server.request.query":
        return REQUEST_QUERY;
      case "server.request.headers.no_cookies":
        return HEADERS_NO_COOKIES;
      case "grpc.server.request.message":
        return GRPC_SERVER_REQUEST_MESSAGE;
      case "grpc.server.request.metadata":
        return GRPC_SERVER_REQUEST_METADATA;
      case "usr.id":
        return USER_ID;
      default:
        return null;
    }
  }
}
