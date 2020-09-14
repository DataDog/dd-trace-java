package datadog.smoketest.springboot.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class LocalInterface implements AutoCloseable {

  private final Server server;

  public LocalInterface() throws IOException {
    this.server = serverBuilder().addService(new Responder()).build().start();
  }

  public int getPort() {
    return server.getPort();
  }

  private static NettyServerBuilder serverBuilder() {
    try {
      return NettyServerBuilder.forAddress(
          new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void close() {
    server.shutdownNow();
  }
}
