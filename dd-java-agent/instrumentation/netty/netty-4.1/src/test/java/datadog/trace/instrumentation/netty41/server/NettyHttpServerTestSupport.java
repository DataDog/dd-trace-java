package datadog.trace.instrumentation.netty41.server;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class NettyHttpServerTestSupport extends AbstractInstrumentationTest {

  private EventLoopGroup eventLoopGroup;
  private Channel serverChannel;
  private int port;

  @BeforeAll
  void startServer() throws Exception {
    eventLoopGroup = new NioEventLoopGroup();
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    configurePipeline(ch);
                  }
                });
    serverChannel = bootstrap.bind(0).sync().channel();
    port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
  }

  @AfterAll
  void stopServer() {
    if (serverChannel != null) {
      serverChannel.close();
    }
    if (eventLoopGroup != null) {
      eventLoopGroup.shutdownGracefully();
    }
  }

  protected abstract void configurePipeline(Channel ch);

  protected Socket connect() throws IOException {
    Socket socket = new Socket("localhost", port);
    socket.setSoTimeout(5000);
    return socket;
  }

  protected static String readHttpResponseBody(InputStream in) throws IOException {
    String headers = readHeaders(in);
    assertOkResponse(headers);
    byte[] body = new byte[contentLength(headers)];
    readFully(in, body);
    return new String(body, UTF_8);
  }

  protected static String readChunkedHttpResponseBody(InputStream in) throws IOException {
    String headers = readHeaders(in);
    assertOkResponse(headers);

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    while (true) {
      int chunkSize = Integer.parseInt(readLine(in), 16);
      if (chunkSize == 0) {
        while (!readLine(in).isEmpty()) {}
        return body.toString(UTF_8.name());
      }
      byte[] chunk = new byte[chunkSize];
      readFully(in, chunk);
      body.write(chunk);
      assertEquals("", readLine(in), "chunk was not followed by CRLF");
    }
  }

  private static void assertOkResponse(String headers) {
    assertTrue(headers.startsWith("HTTP/1.1 200 "), "unexpected response: " + headers);
  }

  protected static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int state = 0;
    while (state < 4) {
      int b = in.read();
      if (b == -1) {
        throw new EOFException("response ended before headers were complete");
      }
      out.write(b);
      if ((state == 0 || state == 2) && b == '\r') {
        state++;
      } else if ((state == 1 || state == 3) && b == '\n') {
        state++;
      } else {
        state = b == '\r' ? 1 : 0;
      }
    }
    return out.toString(US_ASCII.name());
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    boolean seenCarriageReturn = false;
    while (true) {
      int b = in.read();
      if (b == -1) {
        throw new EOFException("response ended before line was complete");
      }
      if (seenCarriageReturn && b == '\n') {
        return out.toString(US_ASCII.name());
      }
      if (seenCarriageReturn) {
        out.write('\r');
        seenCarriageReturn = false;
      }
      if (b == '\r') {
        seenCarriageReturn = true;
      } else {
        out.write(b);
      }
    }
  }

  private static int contentLength(String headers) {
    for (String line : headers.split("\r\n")) {
      int separator = line.indexOf(':');
      if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator))) {
        return Integer.parseInt(line.substring(separator + 1).trim());
      }
    }
    throw new AssertionError("missing content-length header: " + headers);
  }

  private static void readFully(InputStream in, byte[] bytes) throws IOException {
    int read = 0;
    while (read < bytes.length) {
      int count = in.read(bytes, read, bytes.length - read);
      if (count == -1) {
        throw new EOFException("response ended before body was complete");
      }
      read += count;
    }
  }
}
