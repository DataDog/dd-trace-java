package datadog.trace.bootstrap.instrumentation.api;

public class ResourceNamePriorities {
  public static final byte DEFAULT = 0;
  public static final byte HTTP_PATH_NORMALIZER = 1;
  public static final byte HTTP_404 = 2;
  public static final byte HTTP_FRAMEWORK_ROUTE = 3;
  public static final byte RPC_COMMAND_NAME = 3;
  public static final byte HTTP_SERVER_RESOURCE_RENAMING = 4;
  public static final byte HTTP_SERVER_CONFIG_PATTERN_MATCH = 4;
  public static final byte HTTP_CLIENT_CONFIG_PATTERN_MATCH = 4;
  public static final byte TAG_INTERCEPTOR = 5;
  public static final byte MANUAL_INSTRUMENTATION = 6;
}
