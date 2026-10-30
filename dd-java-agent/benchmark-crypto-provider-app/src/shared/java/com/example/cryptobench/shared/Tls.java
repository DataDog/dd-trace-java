package com.example.cryptobench.shared;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class Tls {
  private Tls() {}

  public static SSLContext serverContext(String keyStoreResource) {
    try {
      KeyStore ks = Fixtures.keyStore(keyStoreResource);
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, Fixtures.STORE_PASSWORD);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, null);
      return ctx;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static SSLContext trustAllClientContext() {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {new TrustAll()}, null);
      return ctx;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void configure(SSLParameters params, String protocol, String namedGroup) {
    params.setProtocols(new String[] {protocol});
    if (namedGroup != null && !namedGroup.isEmpty()) {
      params.setNamedGroups(new String[] {namedGroup});
    }
  }

  public static String handshake(
      SSLContext serverCtx, SSLContext clientCtx, String protocol, String namedGroup, byte[] appData)
      throws SSLException {
    SSLEngine client = clientCtx.createSSLEngine();
    client.setUseClientMode(true);
    SSLParameters cp = client.getSSLParameters();
    configure(cp, protocol, namedGroup);
    client.setSSLParameters(cp);

    SSLEngine server = serverCtx.createSSLEngine();
    server.setUseClientMode(false);
    SSLParameters sp = server.getSSLParameters();
    configure(sp, protocol, namedGroup);
    server.setSSLParameters(sp);

    int packet = Math.max(client.getSession().getPacketBufferSize(), 32 * 1024);
    int app = Math.max(client.getSession().getApplicationBufferSize(), appData.length * 2);
    ByteBuffer cToS = ByteBuffer.allocate(packet);
    ByteBuffer sToC = ByteBuffer.allocate(packet);
    ByteBuffer clientIn = ByteBuffer.allocate(app);
    ByteBuffer serverIn = ByteBuffer.allocate(app);
    ByteBuffer clientOut = ByteBuffer.wrap(appData);
    ByteBuffer serverOut = ByteBuffer.wrap(appData);

    client.beginHandshake();
    server.beginHandshake();
    for (int i = 0; i < 1000; i++) {
      step(client, clientOut, cToS);
      step(server, serverOut, sToC);
      cToS.flip();
      sToC.flip();
      unwrap(client, sToC, clientIn);
      unwrap(server, cToS, serverIn);
      cToS.compact();
      sToC.compact();
      if (!clientOut.hasRemaining()
          && !serverOut.hasRemaining()
          && clientIn.position() == appData.length
          && serverIn.position() == appData.length) {
        return client.getSession().getCipherSuite();
      }
    }
    throw new SSLException("handshake did not complete");
  }

  private static void step(SSLEngine e, ByteBuffer src, ByteBuffer dst) throws SSLException {
    SSLEngineResult r = e.wrap(src, dst);
    runTasks(e, r);
  }

  private static void unwrap(SSLEngine e, ByteBuffer src, ByteBuffer dst) throws SSLException {
    while (src.hasRemaining()) {
      SSLEngineResult r = e.unwrap(src, dst);
      runTasks(e, r);
      if (r.getStatus() != SSLEngineResult.Status.OK || r.bytesConsumed() == 0) {
        break;
      }
    }
  }

  private static void runTasks(SSLEngine e, SSLEngineResult r) {
    if (r.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      Runnable task;
      while ((task = e.getDelegatedTask()) != null) {
        task.run();
      }
    }
  }

  static final class TrustAll implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
