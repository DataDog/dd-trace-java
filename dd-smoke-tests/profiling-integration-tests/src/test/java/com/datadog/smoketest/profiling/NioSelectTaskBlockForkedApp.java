// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Forked workload idling a NIO Netty event loop so it repeatedly blocks in Selector.select. */
public final class NioSelectTaskBlockForkedApp {
  public static final String EVENT_LOOP_THREAD = "netty-nio-select-taskblock";

  private static final long PROFILING_STARTUP_DELAY_MILLIS = 1500L;
  private static final int WAKEUP_COUNT = 6;
  private static final long WAKEUP_INTERVAL_MILLIS = 500L;

  private NioSelectTaskBlockForkedApp() {}

  public static void main(String[] args) throws Exception {
    Thread.sleep(PROFILING_STARTUP_DELAY_MILLIS);
    ThreadFactory threadFactory = runnable -> new Thread(runnable, EVENT_LOOP_THREAD);
    NioEventLoopGroup group = new NioEventLoopGroup(1, threadFactory);
    try {
      ServerBootstrap bootstrap =
          new ServerBootstrap()
              .group(group)
              .channel(NioServerSocketChannel.class)
              .childHandler(
                  new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {}
                  });
      Channel serverChannel = bootstrap.bind(0).sync().channel();
      int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
      // With no scheduled task, NioEventLoop's private select(long) falls into
      // Selector.select()'s indefinite, no-arg overload rather than the bounded
      // select(timeout) overload used when a scheduled task is pending. A single
      // multi-second call to that no-arg overload is fragile across JFR chunk
      // rotations, so instead wake the selector repeatedly with short-lived client
      // connections: each connect unblocks one select() call, and the event loop
      // immediately re-enters select() for the next one, yielding several short,
      // independent TaskBlock intervals.
      for (int i = 0; i < WAKEUP_COUNT; i++) {
        Thread.sleep(WAKEUP_INTERVAL_MILLIS);
        try (Socket socket = new Socket()) {
          socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
        }
      }
    } finally {
      group.shutdownGracefully().await(10, TimeUnit.SECONDS);
    }
    Thread.sleep(1500L);
  }
}
