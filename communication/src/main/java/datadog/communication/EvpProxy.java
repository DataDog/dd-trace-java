package datadog.communication;

/** Shared EVP proxy constants. */
public final class EvpProxy {

  public static final String SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";

  /** Identifies the SDK that produced an EVP request. */
  public static final String ORIGIN_HEADER = "DD-EVP-ORIGIN";

  /** Identifies the version of the SDK that produced an EVP request. */
  public static final String ORIGIN_VERSION_HEADER = "DD-EVP-ORIGIN-VERSION";

  /** Origin header value identifying this tracing library. */
  public static final String JAVA_TRACING_LIBRARY = "dd-trace-java";

  /**
   * Default SDK-side target for uncompressed EVP request bodies. Writers may split batches at or
   * below this size to keep Agent proxy requests comfortably bounded.
   */
  public static final int PAYLOAD_SIZE_LIMIT_BYTES = 5 * 1024 * 1024;

  private EvpProxy() {}
}
