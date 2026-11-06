package datadog.trace.instrumentation.azure.cosmos;

import com.azure.cosmos.ConnectionMode;
import com.azure.cosmos.implementation.ConnectionPolicy;
import com.azure.cosmos.implementation.RxDocumentClientImpl;
import com.azure.cosmos.implementation.UserAgentContainer;
import java.net.URI;

/** Client-level details shared by every request sent through one Cosmos client. */
public final class CosmosClientInfo {
  static final String MODE_GATEWAY = "gateway";
  static final String MODE_DIRECT = "direct";
  static final String MODE_OTHER = "other";

  String host;
  int port;
  String connectionMode;
  // the SDK can append suffixes to the user agent after the store models are created
  private final UserAgentContainer userAgentContainer;

  CosmosClientInfo(
      String host, int port, String connectionMode, UserAgentContainer userAgentContainer) {
    this.host = host;
    this.port = port;
    this.connectionMode = connectionMode;
    this.userAgentContainer = userAgentContainer;
  }

  public static CosmosClientInfo create(
      RxDocumentClientImpl client, UserAgentContainer userAgentContainer) {
    URI endpoint = client.getServiceEndpoint();
    ConnectionPolicy connectionPolicy = client.getConnectionPolicy();
    return new CosmosClientInfo(
        endpoint == null ? null : endpoint.getHost(),
        endpoint == null ? -1 : endpoint.getPort(),
        connectionMode(connectionPolicy == null ? null : connectionPolicy.getConnectionMode()),
        userAgentContainer);
  }

  static String connectionMode(ConnectionMode mode) {
    if (mode == ConnectionMode.GATEWAY) {
      return MODE_GATEWAY;
    }
    if (mode == ConnectionMode.DIRECT) {
      return MODE_DIRECT;
    }
    return MODE_OTHER;
  }

  String userAgent() {
    return userAgentContainer == null ? null : userAgentContainer.getUserAgent();
  }
}
