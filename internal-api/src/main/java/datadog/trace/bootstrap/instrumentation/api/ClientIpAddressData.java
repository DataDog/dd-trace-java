package datadog.trace.bootstrap.instrumentation.api;

/**
 * Holds the client IP information resolved during HTTP server request decoration so that consumers
 * (such as AI Guard) can apply it lazily to the local root span without re-running the resolver.
 */
public final class ClientIpAddressData {
  private final String peerIp;
  private final String inferredClientIp;

  public ClientIpAddressData(final String peerIp, final String inferredClientIp) {
    this.peerIp = peerIp;
    this.inferredClientIp = inferredClientIp;
  }

  public String getPeerIp() {
    return peerIp;
  }

  public String getInferredClientIp() {
    return inferredClientIp;
  }
}
