package dd.trace.instrumentation.springwebflux.client;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLException;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

public class AnyCertConnector extends ReactorClientHttpConnector {
  public AnyCertConnector(InetSocketAddress proxy) {
    super(
        options -> {
          try {
            if (proxy != null) {
              options.httpProxy(addressSpec -> addressSpec.address(proxy));
            }
            options.sslContext(
                SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build());
          } catch (SSLException e) {
            e.printStackTrace();
          }
        });
  }
}
