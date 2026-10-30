package com.example.cryptobench.difftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.cryptobench.shared.Accp;
import com.example.cryptobench.shared.Fixtures;
import com.example.cryptobench.shared.Tls;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TlsInteropTest {
  static Provider accp;

  @BeforeAll
  static void installAccp() throws Exception {
    accp = Accp.install();
    assertEquals(Accp.NAME, Signature.getInstance("SHA256withRSA").getProvider().getName());
    assertEquals(Accp.NAME, Signature.getInstance("SHA256withECDSA").getProvider().getName());
    assertEquals(Accp.NAME, Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName());
    assertEquals(Accp.NAME, Mac.getInstance("HmacSHA256").getProvider().getName());
  }

  @AfterAll
  static void removeAccp() {
    Security.removeProvider(Accp.NAME);
  }

  @ParameterizedTest(name = "accp server, jdk client: {0} {1} {2}")
  @CsvSource({
    "TLSv1.3, rsa, default",
    "TLSv1.3, rsa, secp256r1",
    "TLSv1.3, ec, default",
    "TLSv1.3, ec, secp256r1",
    "TLSv1.2, rsa, default",
    "TLSv1.2, rsa, secp256r1",
    "TLSv1.2, ec, default",
    "TLSv1.2, ec, secp256r1",
  })
  void accpServerJdkClient(String protocol, String cert, String group) throws Exception {
    SSLServerSocket ss =
        (SSLServerSocket)
            Tls.serverContext("ec".equals(cert) ? Fixtures.EC_STORE : Fixtures.RSA_STORE)
                .getServerSocketFactory()
                .createServerSocket();
    ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    ss.setSoTimeout(30_000);
    String[] seen = new String[1];
    Thread t =
        new Thread(
            () -> {
              try (SSLSocket s = (SSLSocket) ss.accept()) {
                TlsPeer.echo(s);
                seen[0] = s.getSession().getProtocol();
              } catch (Exception e) {
                seen[0] = "error " + e;
              }
            });
    t.start();
    List<String> lines =
        runPeer("client", Integer.toString(ss.getLocalPort()), protocol, group, "hello-" + cert);
    t.join(30_000);
    ss.close();
    assertJdkOnly(lines);
    String reply = lines.get(lines.size() - 1);
    assertTrue(reply.startsWith(protocol + " ") && reply.endsWith(" hello-" + cert), reply);
    assertEquals(protocol, seen[0]);
  }

  @ParameterizedTest(name = "jdk server, accp client: {0} {1} {2}")
  @CsvSource({
    "TLSv1.3, rsa, default",
    "TLSv1.3, rsa, secp256r1",
    "TLSv1.3, ec, default",
    "TLSv1.3, ec, secp256r1",
    "TLSv1.2, rsa, default",
    "TLSv1.2, rsa, secp256r1",
    "TLSv1.2, ec, default",
    "TLSv1.2, ec, secp256r1",
  })
  void jdkServerAccpClient(String protocol, String cert, String group) throws Exception {
    Process p = startPeer("server", cert);
    try {
      BufferedReader out =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
      List<String> lines = new ArrayList<>();
      String line;
      int port = -1;
      while ((line = out.readLine()) != null) {
        lines.add(line);
        if (line.startsWith("port=")) {
          port = Integer.parseInt(line.substring(5));
          break;
        }
      }
      assertJdkOnly(lines);
      assertTrue(port > 0, "peer did not report a port: " + lines);
      String reply = TlsPeer.client(port, protocol, group, "hello-" + cert);
      assertTrue(reply.startsWith(protocol + " ") && reply.endsWith(" hello-" + cert), reply);
    } finally {
      p.getOutputStream().close();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
      }
    }
  }

  private static void assertJdkOnly(List<String> peerOutput) {
    assertTrue(
        peerOutput.size() > 0 && peerOutput.get(0).startsWith("providers[0]=")
            && !peerOutput.get(0).contains(Accp.NAME),
        "peer must use JDK providers only: " + peerOutput);
  }

  private static Process startPeer(String... args) throws Exception {
    String java = ProcessHandle.current().info().command().orElse("java");
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    cmd.add("-cp");
    cmd.add(System.getProperty("bench.test.classpath", System.getProperty("java.class.path")));
    cmd.add(TlsPeer.class.getName());
    for (String a : args) {
      cmd.add(a);
    }
    return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).directory(new File(".")).start();
  }

  private static List<String> runPeer(String... args) throws Exception {
    Process p = startPeer(args);
    p.getOutputStream().close();
    List<String> lines = new ArrayList<>();
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        lines.add(line);
      }
    }
    assertTrue(p.waitFor(30, TimeUnit.SECONDS), "peer hung");
    assertEquals(0, p.exitValue(), "peer failed: " + lines);
    return lines;
  }
}
