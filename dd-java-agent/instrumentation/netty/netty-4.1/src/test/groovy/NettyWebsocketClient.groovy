import datadog.trace.agent.test.base.WebsocketClient
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch

import static io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory.newHandshaker

class NettyWebsocketClient implements WebsocketClient {
  static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(NettyWebsocketClient.name, LogLevel.DEBUG)

  static class WebsocketHandler extends SimpleChannelInboundHandler<Object> {
    final URI uri
    WebSocketClientHandshaker handshaker
    def handshaken = new CountDownLatch(1)

    WebsocketHandler(uri) {
      this.uri = uri
    }

    @Override
    void channelActive(ChannelHandlerContext ctx) throws Exception {
      handshaker = newHandshaker(
      uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders()
      .add("User-Agent", "dd-trace-java"), // keep me
      1280000)
      handshaker.handshake(ctx.channel())
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
      final Channel ch = ctx.channel()
      if (!handshaker.isHandshakeComplete()) {
        // web socket client connected
        handshaker.finishHandshake(ch, (FullHttpResponse) msg)
      }
      handshaken.countDown()
    }
  }
  final eventLoopGroup = new NioEventLoopGroup()

  Channel channel
  def chunkSize = -1

  @Override
  void connect(String url) {
    def uri = new URI(url)
    def wsHandler = new WebsocketHandler(uri)
    Bootstrap b = new Bootstrap()
    b.group(eventLoopGroup)
    .handler(LOGGING_HANDLER)
    .handler(new ChannelInitializer() {
      protected void initChannel(Channel ch) throws Exception {
        def pipeline = ch.pipeline()
        pipeline.addLast(new HttpClientCodec())
        pipeline.addLast(new HttpObjectAggregator(1024))
        pipeline.addLast(wsHandler)
        // remove our handler since we do not want to trace that client
        pipeline.names().findAll { it.contains("HttpClientTracingHandler") }.each { pipeline.remove(it) }
      }
    }).channel(NioSocketChannel)
    channel = b.connect(uri.host, uri.port).sync().channel()
    //wait for the handshake to complete properly
    wsHandler.handshaken.await()
  }

  @Override
  void send(String text) {
    def chunks = split(text.getBytes(StandardCharsets.UTF_8))
    channel.writeAndFlush(new TextWebSocketFrame(chunks.length == 1, 0, Unpooled.wrappedBuffer(chunks[0])))
    for (def i = 1; i < chunks.length; i++) {
      channel.writeAndFlush(new ContinuationWebSocketFrame(chunks.length - 1 == i, 0, Unpooled.wrappedBuffer(chunks[i])))
    }
  }

  @Override
  void send(byte[] bytes) {
    def chunks = split(bytes)
    channel.writeAndFlush(new BinaryWebSocketFrame(chunks.length == 1, 0, Unpooled.wrappedBuffer(chunks[0]))).sync()
    for (def i = 1; i < chunks.length; i++) {
      channel.writeAndFlush(new ContinuationWebSocketFrame(chunks.length - 1 == i, 0, Unpooled.wrappedBuffer(chunks[i]))).sync()
    }
  }

  byte[][] split(byte[] src) {
    if (chunkSize <= 0) {
      return new byte[][]{src}
    }
    def ret = new byte[(int) Math.ceil(src.length / chunkSize)][]
    def offset = 0
    for (def i = 0; i < ret.length; i++) {
      ret[i] = new byte[Math.min(src.length - offset, chunkSize)]
      System.arraycopy(src, offset, ret[i], 0, ret[i].length)
    }
    ret
  }

  @Override
  void close(int code, String reason) {
    channel.writeAndFlush(new CloseWebSocketFrame(code, reason)).sync()
    channel.close()
  }

  @Override
  boolean supportMessageChunks() {
    true
  }

  @Override
  void setSplitChunksAfter(int size) {
    chunkSize = size
  }
}
