package datadog.trace.security;

public class SecTags {

  public static final String SEC_PREFIX = "dd.sec.";

  public static final String REACTIVE_VER               = SEC_PREFIX + "reactive.version";

  public static final String REQUEST_BODY               = SEC_PREFIX + "server.request.body";
  public static final String REQUEST_BODY_RAW           = SEC_PREFIX + "server.request.body.raw";
  public static final String REQUEST_TRACING_ID         = SEC_PREFIX + "server.request.tracing_identifier";
  public static final String REQUEST_QUERY              = SEC_PREFIX + "server.request.query";
  public static final String REQUEST_HEADERS            = SEC_PREFIX + "server.request.headers.no_cookies";
  public static final String REQUEST_TRAILERS           = SEC_PREFIX + "server.request.trailers";
  public static final String REQUEST_URI                = SEC_PREFIX + "server.request.uri.raw";
  public static final String REQUEST_CLIENT_IP          = SEC_PREFIX + "server.request.client_ip";
  public static final String REQUEST_METHOD             = SEC_PREFIX + "server.request.method";
  public static final String REQUEST_FILES_FIELDS       = SEC_PREFIX + "server.request.body.files_field_names";
  public static final String REQUEST_FILENAMES          = SEC_PREFIX + "server.request.body.filenames";
  public static final String REQUEST_COMBINED_FILE_SIZE = SEC_PREFIX + "server.request.body.combined_file_size";
  public static final String REQUEST_PATH_PARAMS        = SEC_PREFIX + "server.request.path_params";
  public static final String REQUEST_COOKIES            = SEC_PREFIX + "server.request.cookies";
  public static final String REQUEST_TRANSPORT          = SEC_PREFIX + "server.request.transport";

  public static final String RESPONSE_STATUS            = SEC_PREFIX + "server.response.status";
  public static final String RESPONSE_BODY_RAW          = SEC_PREFIX + "server.response.body.raw";
  public static final String RESPONSE_HEADERS           = SEC_PREFIX + "server.response.headers.no_cookies";
  public static final String RESPONSE_TRAILERS          = SEC_PREFIX + "server.response.trailers";
  public static final String RESPONSE_WRITE             = SEC_PREFIX + "server.response.write";
  public static final String RESPONSE_END               = SEC_PREFIX + "server.response.end";

  public static final String META_ERROR                 = SEC_PREFIX + "server.meta.error";


}
