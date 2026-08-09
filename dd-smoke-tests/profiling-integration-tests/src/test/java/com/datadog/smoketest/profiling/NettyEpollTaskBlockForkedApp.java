// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Forked workload idling a native-epoll Netty event loop so it repeatedly blocks in epollWait. */
public final class NettyEpollTaskBlockForkedApp {
  public static final String EVENT_LOOP_THREAD = "netty-epoll-taskblock";

  private static final long PROFILING_STARTUP_DELAY_MILLIS = 1500L;
  private static final long IDLE_MILLIS = 3000L;

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
      bootstrap.bind(0).sync().channel();
      TimeUnit.MILLISECONDS.sleep(IDLE_MILLIS);
    } finally {
      group.shutdownGracefully().await(10, TimeUnit.SECONDS);
    }
    Thread.sleep(1500L);
  }
}
