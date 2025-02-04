package server

import datadog.trace.agent.test.utils.OkHttpUtils
import spock.lang.Ignore

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

// http2 seems to be not supported from the tracer
@Ignore
class IastVertxHttp2ServerTest extends IastVertxHttpServerTest {

  def setupSpec() {
    final trustManager = trustManager()
    client = OkHttpUtils.clientBuilder()
      .sslSocketFactory(socketFactory(trustManager), trustManager)
      .hostnameVerifier(hostnameVerifier())
      .build()
  }

  @Override
  boolean isHttps() {
    true
  }

  private static HostnameVerifier hostnameVerifier() {
    return new HostnameVerifier() {
        @Override
        boolean verify(String s, SSLSession sslSession) {
          true
        }
      }
  }

  private static X509TrustManager trustManager() {
    return new X509TrustManager() {
        @Override
        void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0]
        }
      }
  }

  private static SSLSocketFactory socketFactory(final X509TrustManager trustManager) {
    final sslContext = SSLContext.getInstance("TLSv1.2")
    sslContext.init(null, [trustManager].toArray(new TrustManager[0]), new SecureRandom())
    return sslContext.getSocketFactory()
  }
}
