package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface KnownAddresses {
  /** Body, as parsed by the server/framework. */
  Address<Object> REQUEST_BODY_OBJECT = new Address<>("server.request.body");

  /**
   * Body, as parsed by the server/framework, converted into a map. The hierarchy of the map is
   * flattened. The structure:
   *
   * <p>{ a: { b: 3 }}
   *
   * <p>should be converted to:
   *
   * <p>{ a.b: [3] }
   *
   * <p>Non-standard.
   */
  Address<Map<String, Collection<String>>> REQUEST_BODY_OBJECT_MAP =
      new Address<>("server.request.body.map");

  /** The first characters of the raw HTTP body */
  Address<String> REQUEST_BODY_RAW = new Address<>("server.request.body.raw");

  /** The unparsed request uri, incl. the query string. */
  Address<String> REQUEST_URI_RAW = new Address<>("server.request.uri.raw");

  /** The deduced IP address of the client. */
  Address<String> REQUEST_CLIENT_IP = new Address<>("server.request.client_ip");

  /** The verb of the HTTP request. */
  Address<String> REQUEST_METHOD = new Address<>("server.request.method");

  /**
   * incoming url parameters as parsed by the framework (e.g /foo/:id gives a id param that is not
   * part of the query
   */
  Address<Map<String, String>> REQUEST_PATH_PARAMS = new Address<>("server.request.path_params");

  /** Cookies as parsed by the server */
  Address<List<StringKVPair>> REQUEST_COOKIES = new Address<>("server.request.cookies");

  /** Same as server transport related field. */
  Address<String> REQUEST_TRANSPORT = new Address<String>("server.request.transport");

  /** status code of HTTP response */
  Address<Integer> RESPONSE_STATUS = new Address<>("server.response.status");

  /** First chars of HTTP response body */
  Address<String> RESPONSE_BODY_RAW = new Address<>("server.response.body.raw");

  /** Reponse headers excluding cookies */
  Address<Map<String, Collection<String>>> RESPONSE_HEADERS_NO_COOKIES =
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
  Address<Map<String, List<String>>> REQUEST_QUERY = new Address("server.request.query");

  /** Headers with the cookie fields excluded. */
  Address<CaseInsensitiveMap<List<String>>> HEADERS_NO_COOKIES =
      new Address<>("server.request.headers.no_cookies");
}
