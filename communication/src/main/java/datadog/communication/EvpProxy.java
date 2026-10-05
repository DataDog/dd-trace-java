package datadog.communication;

/** Shared EVP proxy constants. */
public final class EvpProxy {

  public static final String SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";

  /**
   * Default SDK-side target for uncompressed EVP request bodies. Writers may split batches at or
   * below this size to keep Agent proxy requests comfortably bounded.
   */
  public static final int PAYLOAD_SIZE_LIMIT_BYTES = 5 * 1024 * 1024;

  private EvpProxy() {}
}
