package datadog.communication.http;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.stream.Stream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ForbiddenCertificatesHostnameVerifierTest {
  private static HostnameVerifier hostnameVerifier;
  private static X509Certificate localhostCertificate;
  private static SSLContext sslContext;
  private static X509TrustManager trustManager;

  @BeforeAll
  static void setUpTls() throws Exception {
    hostnameVerifier = OkHttpUtils.buildHttpClient(false, null, null, 5000).hostnameVerifier();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    char[] password = "datadog".toCharArray();
    try (InputStream input = resource("localhost.p12")) {
      keyStore.load(input, password);
    }
    localhostCertificate = (X509Certificate) keyStore.getCertificate("localhost");

    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, password);
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(keyStore);
    trustManager = (X509TrustManager) trustManagers.getTrustManagers()[0];
    sslContext = SSLContext.getInstance("TLS");
    sslContext.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
  }

  @ParameterizedTest
  @MethodSource("compromisedCertificates")
  void rejectsCompromisedIntermediateKeysIncludingCrossSignatures(X509Certificate compromised) {
    assertFalse(hostnameVerifier.verify("localhost", session(localhostCertificate, compromised)));
    assertFalse(
        hostnameVerifier.verify(
            "localhost", session(localhostCertificate, localhostCertificate, compromised)));
  }

  @Test
  void preservesHostnameVerification() {
    assertTrue(hostnameVerifier.verify("localhost", session(localhostCertificate)));
    assertFalse(hostnameVerifier.verify("different.example", session(localhostCertificate)));
  }

  @Test
  void rejectsUnverifiedPeer() {
    assertFalse(hostnameVerifier.verify("localhost", session()));
  }

  static Stream<X509Certificate> compromisedCertificates() throws Exception {
    try (InputStream input = resource("emudhra-compromised-certificates.pem")) {
      return CertificateFactory.getInstance("X.509").generateCertificates(input).stream()
          .map(X509Certificate.class::cast);
    }
  }

  private static InputStream resource(String name) {
    return ForbiddenCertificatesHostnameVerifierTest.class.getResourceAsStream("/" + name);
  }

  private static SSLSession session(Certificate... certificates) {
    return (SSLSession)
        Proxy.newProxyInstance(
            SSLSession.class.getClassLoader(),
            new Class<?>[] {SSLSession.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("getPeerCertificates")) {
                throw new UnsupportedOperationException(method.getName());
              }
              if (certificates.length == 0) {
                throw new SSLPeerUnverifiedException("no peer");
              }
              return certificates;
            });
  }

  @Nested
  class HttpConnections {
    private MockWebServer server;
    private OkHttpClient client;

    @BeforeEach
    void setUp() {
      server = new MockWebServer();
    }

    @AfterEach
    void tearDown() throws Exception {
      if (client != null) {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
      }
      server.shutdown();
    }

    @Test
    void acceptsTrustedHttps() throws Exception {
      startHttpsServer();
      server.enqueue(new MockResponse().setBody("accepted"));

      try (Response response = client.newCall(secretRequest()).execute()) {
        assertEquals("accepted", response.body().string());
      }

      RecordedRequest request = server.takeRequest();
      assertEquals("secret-api-key", request.getHeader("DD-API-KEY"));
      assertEquals("secret-body", request.getBody().readUtf8());
    }

    @Test
    void rejectsForbiddenKeyBeforeSendingHeadersOrBody() throws Exception {
      startHttpsServer();
      // Block our test key to exercise real TLS without using compromised private keys.
      ByteString forbiddenKey =
          ByteString.of(localhostCertificate.getPublicKey().getEncoded()).sha256();
      client =
          client
              .newBuilder()
              .hostnameVerifier(new ForbiddenCertificatesHostnameVerifier(singleton(forbiddenKey)))
              .build();

      assertThrows(
          SSLPeerUnverifiedException.class, () -> client.newCall(secretRequest()).execute());
      assertEquals(0, server.getRequestCount());
    }

    @Test
    void acceptsCleartextHttp() throws Exception {
      server.start();
      server.enqueue(new MockResponse().setBody("accepted"));
      client = OkHttpUtils.buildHttpClient(server.url("/"), 5000);

      try (Response response = client.newCall(secretRequest()).execute()) {
        assertEquals("accepted", response.body().string());
      }
    }

    private void startHttpsServer() throws Exception {
      server.useHttps(sslContext.getSocketFactory(), false);
      server.start();
      client =
          OkHttpUtils.buildHttpClient(server.url("/"), 5000)
              .newBuilder()
              .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
              .build();
    }

    private Request secretRequest() {
      return new Request.Builder()
          .url(server.url("/intake"))
          .header("DD-API-KEY", "secret-api-key")
          .post(RequestBody.create(null, "secret-body"))
          .build();
    }
  }
}
