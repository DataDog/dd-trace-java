// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Forked workload that blocks a platform thread in native accept and socket-read calls. */
public final class NativeIoTaskBlockForkedApp {
  private static final int ITERATIONS = 20;

  public static void main(String[] args) throws Exception {
    Thread.currentThread().setName("native-io-spanless");
    for (int i = 0; i < ITERATIONS; i++) {
      runBlockingAcceptAndRead();
    }
    Thread.sleep(1500);
  }

  private static void runBlockingAcceptAndRead() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread client =
          new Thread(
              () -> {
                try {
                  Thread.sleep(50L);
                  try (Socket socket =
                      new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                    Thread.sleep(50L);
                    OutputStream output = socket.getOutputStream();
                    output.write(1);
                    output.flush();
                  }
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              "native-io-client");
      client.start();

      try (Socket accepted = server.accept()) {
        accepted.setSoTimeout(5000);
        InputStream input = accepted.getInputStream();
        if (input.read() != 1) {
          throw new IllegalStateException("Unexpected socket payload");
        }
      }
      client.join();
    }
  }
}
