package com.example.cryptobench.difftest;

import com.example.cryptobench.shared.Fixtures;
import com.example.cryptobench.shared.Tls;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

public final class TlsPeer {
  public static void main(String[] args) throws Exception {
    PrintStream out = System.out;
    out.println("providers[0]=" + Security.getProviders()[0].getName());
    if ("server".equals(args[0])) {
      server(args[1], out);
    } else {
      out.println(client(Integer.parseInt(args[1]), args[2], args[3], args[4]));
    }
    out.flush();
  }

  static void server(String cert, PrintStream out) throws Exception {
    SSLServerSocket ss =
        (SSLServerSocket)
            Tls.serverContext("ec".equals(cert) ? Fixtures.EC_STORE : Fixtures.RSA_STORE)
                .getServerSocketFactory()
                .createServerSocket();
    ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    ss.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    out.println("port=" + ss.getLocalPort());
    out.flush();
    Thread stopper =
        new Thread(
            () -> {
              try {
                while (System.in.read() >= 0) {
                }
                ss.close();
              } catch (Exception ignored) {
              }
            });
    stopper.setDaemon(true);
    stopper.start();
    while (!ss.isClosed()) {
      try (SSLSocket s = (SSLSocket) ss.accept()) {
        echo(s);
      } catch (java.net.SocketException closed) {
        break;
      }
    }
  }

  static void echo(SSLSocket s) throws Exception {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    String line = in.readLine();
    OutputStream o = s.getOutputStream();
    o.write(
        (s.getSession().getProtocol() + " " + s.getSession().getCipherSuite() + " " + line + "\n")
            .getBytes(StandardCharsets.UTF_8));
    o.flush();
  }

  static String client(int port, String protocol, String group, String message) throws Exception {
    try (SSLSocket s =
        (SSLSocket)
            Tls.trustAllClientContext()
                .getSocketFactory()
                .createSocket(InetAddress.getLoopbackAddress(), port)) {
      SSLParameters p = s.getSSLParameters();
      Tls.configure(p, protocol, "default".equals(group) ? null : group);
      s.setSSLParameters(p);
      s.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
      s.getOutputStream().flush();
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
      return in.readLine();
    }
  }
}
