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

  Address<Object> RESPONSE_BODY_OBJECT = new Address<>("server.response.body");

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

  Address<Object> GRPC_SERVER_METHOD = new Address<>("grpc.server.method");

  Address<Object> GRPC_SERVER_REQUEST_MESSAGE = new Address<>("grpc.server.request.message");

  // XXX: Not really used yet, but it's a known address and we should not treat it as unknown.
  Address<Object> GRPC_SERVER_REQUEST_METADATA = new Address<>("grpc.server.request.metadata");

  // XXX: Not really used yet, but it's a known address and we should not treat it as unknown.
  Address<Object> GRAPHQL_SERVER_ALL_RESOLVERS = new Address<>("graphql.server.all_resolvers");

  // XXX: Not really used yet, but it's a known address and we should not treat it as unknown.
  Address<Object> GRAPHQL_SERVER_RESOLVER = new Address<>("graphql.server.resolver");

  Address<Map<String, ?>> SERVER_GRAPHQL_ALL_RESOLVERS =
      new Address<>("server.graphql.all_resolvers");

  Address<String> USER_ID = new Address<>("usr.id");

  Address<String> USER_LOGIN = new Address<>("usr.login");

  Address<String> SESSION_ID = new Address<>("usr.session_id");

  /** The URL of a network resource being requested (outgoing request) */
  Address<String> IO_NET_URL = new Address<>("server.io.net.url");

  /** The headers of a network resource being requested (outgoing request) */
  Address<Map<String, List<String>>> IO_NET_REQUEST_HEADERS =
      new Address<>("server.io.net.request.headers");

  /** The method of a network resource being requested (outgoing request) */
  Address<String> IO_NET_REQUEST_METHOD = new Address<>("server.io.net.request.method");

  /** The body of a network resource being requested (outgoing request) */
  Address<Object> IO_NET_REQUEST_BODY = new Address<>("server.io.net.request.body");

  /** The status of a network resource being requested (outgoing request) */
  Address<String> IO_NET_RESPONSE_STATUS = new Address<>("server.io.net.response.status");

  /** The response headers of a network resource being requested (outgoing request) */
  Address<Map<String, List<String>>> IO_NET_RESPONSE_HEADERS =
      new Address<>("server.io.net.response.headers");

  /** The response body of a network resource being requested (outgoing request) */
  Address<Object> IO_NET_RESPONSE_BODY = new Address<>("server.io.net.response.body");

  /** The representation of opened file on the filesystem */
  Address<String> IO_FS_FILE = new Address<>("server.io.fs.file");

  /** The database type (ex: mysql, postgresql, sqlite) */
  Address<String> DB_TYPE = new Address<>("server.db.system");

  /** The SQL query being executed */
  Address<String> DB_SQL_QUERY = new Address<>("server.db.statement");

  /** Login failure business event */
  Address<String> LOGIN_FAILURE = new Address<>("server.business_logic.users.login.failure");

  /** Login success business event */
  Address<String> LOGIN_SUCCESS = new Address<>("server.business_logic.users.login.success");

  /** Signup business event */
  Address<String> SIGN_UP = new Address<>("server.business_logic.users.signup");

  /** The Exec command being executed */
  Address<String> EXEC_CMD = new Address<>("server.sys.exec.cmd");

  /** The Shell command being executed */
  Address<String> SHELL_CMD = new Address<>("server.sys.shell.cmd");

  Address<Map<String, Object>> WAF_CONTEXT_PROCESSOR = new Address<>("waf.context.processor");

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
      case "server.response.body":
        return RESPONSE_BODY_OBJECT;
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
      case "grpc.server.method":
        return GRPC_SERVER_METHOD;
      case "grpc.server.request.message":
        return GRPC_SERVER_REQUEST_MESSAGE;
      case "grpc.server.request.metadata":
        return GRPC_SERVER_REQUEST_METADATA;
      case "graphql.server.all_resolvers":
        return GRAPHQL_SERVER_ALL_RESOLVERS;
      case "graphql.server.resolver":
        return GRAPHQL_SERVER_RESOLVER;
      case "server.graphql.all_resolvers":
        return SERVER_GRAPHQL_ALL_RESOLVERS;
      case "usr.id":
        return USER_ID;
      case "usr.login":
        return USER_LOGIN;
      case "usr.session_id":
        return SESSION_ID;
      case "server.io.net.url":
        return IO_NET_URL;
      case "server.io.net.request.headers":
        return IO_NET_REQUEST_HEADERS;
      case "server.io.net.request.method":
        return IO_NET_REQUEST_METHOD;
      case "server.io.net.request.body":
        return IO_NET_REQUEST_BODY;
      case "server.io.net.response.status":
        return IO_NET_RESPONSE_STATUS;
      case "server.io.net.response.headers":
        return IO_NET_RESPONSE_HEADERS;
      case "server.io.net.response.body":
        return IO_NET_RESPONSE_BODY;
      case "server.io.fs.file":
        return IO_FS_FILE;
      case "server.db.system":
        return DB_TYPE;
      case "server.db.statement":
        return DB_SQL_QUERY;
      case "waf.context.processor":
        return WAF_CONTEXT_PROCESSOR;
      case "server.business_logic.users.login.success":
        return LOGIN_SUCCESS;
      case "server.business_logic.users.login.failure":
        return LOGIN_FAILURE;
      case "server.business_logic.users.signup":
        return SIGN_UP;
      case "server.sys.exec.cmd":
        return EXEC_CMD;
      case "server.sys.shell.cmd":
        return SHELL_CMD;
      default:
        return null;
    }
  }
}
