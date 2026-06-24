package datadog.trace.bootstrap.instrumentation.api;

public final class ServiceNameSources {
  public static final CharSequence DB_CLIENT_SPLIT_BY_HOST =
      UTF8BytesString.create("opt.db_client_split_by_host");
  public static final CharSequence DB_CLIENT_SPLIT_BY_INSTANCE =
      UTF8BytesString.create("opt.db_client_split_by_instance");
  public static final CharSequence HTTP_CLIENT_SPLIT_BY_DOMAIN =
      UTF8BytesString.create("opt.http_client_split_by_domain");
  public static final CharSequence JEE_SPLIT_BY_DEPLOYMENT =
      UTF8BytesString.create("opt.jee_split_by_deployment");
  public static final CharSequence MESSAGE_BROKER_SPLIT_BY_DESTINATION =
      UTF8BytesString.create("opt.message_broker_split_by_destination");
  public static final CharSequence SPLIT_BY_SERVLET_CONTEXT =
      UTF8BytesString.create("opt.split_by_servlet_context");
  public static final CharSequence SPLIT_BY_TAGS = UTF8BytesString.create("opt.split_by_tags");
  public static final CharSequence MANUAL = UTF8BytesString.create("m");

  private ServiceNameSources() {
    // utility class - no instantiation
  }
}
