package datadog.trace.instrumentation.azure.cosmos;

import com.azure.cosmos.implementation.RxDocumentClientImpl;
import com.azure.cosmos.implementation.UserAgentContainer;
import java.net.URI;

/** Client-level details shared by every request sent through one Cosmos store model. */
public final class CosmosClientInfo {
  public static final String MODE_GATEWAY = "gateway";
  public static final String MODE_DIRECT = "direct";

  final String host;
  final int port;
  final String connectionMode;
  // the SDK can append suffixes to the user agent after the store models are created
  private final UserAgentContainer userAgentContainer;

  CosmosClientInfo(
      String host, int port, String connectionMode, UserAgentContainer userAgentContainer) {
    this.host = host;
    this.port = port;
    this.connectionMode = connectionMode;
    this.userAgentContainer = userAgentContainer;
  }

  /**
   * The connection mode is the one of the store model, not of the client: a direct mode client
   * still sends its metadata requests through the gateway store model.
   */
  public static CosmosClientInfo create(
      RxDocumentClientImpl client, String connectionMode, UserAgentContainer userAgentContainer) {
    URI endpoint = client.getServiceEndpoint();
    return new CosmosClientInfo(
        endpoint == null ? null : endpoint.getHost(),
        endpoint == null ? -1 : endpoint.getPort(),
        connectionMode,
        userAgentContainer);
  }

  String userAgent() {
    return userAgentContainer == null ? null : userAgentContainer.getUserAgent();
  }
}
