package server;

import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.SelfSignedCertificate;

public class VertxSecureTestServer extends VertxTestServer {

  @Override
  protected HttpServerOptions httpServerOptions() {
    final HttpServerOptions serverOptions = super.httpServerOptions();
    final SelfSignedCertificate certificate = SelfSignedCertificate.create();
    serverOptions.setSsl(true);
    serverOptions.setUseAlpn(true);
    serverOptions.setTrustOptions(certificate.trustOptions());
    serverOptions.setKeyCertOptions(certificate.keyCertOptions());
    return serverOptions;
  }
}
