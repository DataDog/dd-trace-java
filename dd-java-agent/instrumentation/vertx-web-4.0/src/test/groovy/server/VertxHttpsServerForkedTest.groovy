package server

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.utils.OkHttpUtils
import io.vertx.core.AbstractVerticle
import okhttp3.OkHttpClient

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeoutException

class VertxHttpsServerForkedTest extends VertxHttpServerForkedTest {

  def setupSpec() {
    client = buildInsecureClient()
  }

  @Override
  protected Class<AbstractVerticle> verticle() {
    VertxSecureTestServer
  }

  @Override
  HttpServer server() {
    final server = super.server()
    return new HttpServer() {
        @Override
        void start() throws TimeoutException {
          server.start()
        }

        @Override
        void stop() {
          server.stop()
        }

        @Override
        URI address() {
          final address = server.address()
          return new URI(address.toString().replaceFirst("^http", "https"))
        }
      }
  }

  protected OkHttpClient buildInsecureClient() {
    final trustManager = new X509TrustManager() {
        @Override
        void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {}

        @Override
        void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {}

        @Override
        X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0]
        }
      }
    final verifier = new HostnameVerifier() {
        @Override
        boolean verify(String s, SSLSession sslSession) {
          true
        }
      }
    final context = SSLContext.getInstance('TLSv1.2')
    context.init(null, [trustManager].toArray(new TrustManager[0]), null)
    return OkHttpUtils.clientBuilder()
      .sslSocketFactory(context.socketFactory, trustManager)
      .hostnameVerifier(verifier)
      .build()
  }
}
