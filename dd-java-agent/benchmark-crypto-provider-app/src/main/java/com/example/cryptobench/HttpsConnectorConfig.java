package com.example.cryptobench;

import com.example.cryptobench.shared.Fixtures;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpsConnectorConfig {

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpsConnector(
      @Value("${bench.https.port}") int port, @Value("${bench.https.cert:rsa}") String cert) {
    return factory -> factory.addAdditionalTomcatConnectors(connector(port, cert));
  }

  private static Connector connector(int port, String cert) {
    String resource = "ec".equals(cert) ? Fixtures.EC_STORE : Fixtures.RSA_STORE;
    File keyStore = extract(resource);
    Connector connector = new Connector(Http11NioProtocol.class.getName());
    connector.setPort(port);
    connector.setScheme("https");
    connector.setSecure(true);
    connector.setProperty("SSLEnabled", "true");
    connector.setProperty(
        "sslImplementationName", "org.apache.tomcat.util.net.jsse.JSSEImplementation");
    SSLHostConfig host = new SSLHostConfig();
    host.setSslProtocol("TLS");
    host.setProtocols("TLSv1.2+TLSv1.3");
    SSLHostConfigCertificate c = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
    c.setCertificateKeystoreFile(keyStore.getAbsolutePath());
    c.setCertificateKeystorePassword(new String(Fixtures.STORE_PASSWORD));
    c.setCertificateKeystoreType("PKCS12");
    c.setCertificateKeyAlias("bench");
    host.addCertificate(c);
    connector.addSslHostConfig(host);
    return connector;
  }

  private static File extract(String resource) {
    try (InputStream in = HttpsConnectorConfig.class.getClassLoader().getResourceAsStream(resource)) {
      File f = File.createTempFile("crypto-bench-", ".p12");
      f.deleteOnExit();
      Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return f;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
