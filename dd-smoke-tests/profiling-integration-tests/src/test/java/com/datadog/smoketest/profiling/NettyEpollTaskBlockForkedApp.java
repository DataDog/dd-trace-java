// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Forked workload idling a native-epoll Netty event loop so it repeatedly blocks in epollWait. */
public final class NettyEpollTaskBlockForkedApp {
  public static final String EVENT_LOOP_THREAD = "netty-epoll-taskblock";

  private static final long PROFILING_STARTUP_DELAY_MILLIS = 1500L;
  private static final int WAKEUP_COUNT = 6;
  private static final long WAKEUP_INTERVAL_MILLIS = 500L;

  private NettyEpollTaskBlockForkedApp() {}

  public static void main(String[] args) throws Exception {
    Thread.sleep(PROFILING_STARTUP_DELAY_MILLIS);
    ThreadFactory threadFactory = runnable -> new Thread(runnable, EVENT_LOOP_THREAD);
    EpollEventLoopGroup group = new EpollEventLoopGroup(1, threadFactory);
    try {
      ServerBootstrap bootstrap =
          new ServerBootstrap()
              .group(group)
              .channel(EpollServerSocketChannel.class)
              .childHandler(
                  new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {}
                  });
      Channel serverChannel = bootstrap.bind(0).sync().channel();
      int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
      // A single multi-second epollWait is fragile: if the profiler's recording hasn't
      // started yet when the one-and-only block begins, beginTaskBlock() is rejected and
      // the whole run yields zero events, with no second chance. Instead, wake the event
      // loop repeatedly with short-lived client connections: each connect unblocks one
      // epollWait call, and the loop immediately re-enters epollWait for the next one,
      // yielding several short, independent TaskBlock intervals.
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
