// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.nettyepoll;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
@WithConfig(key = PROFILING_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_PRECHECK, value = "false")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, value = "false")
class NettyEpollProfilingDisabledForkedTest extends AbstractInstrumentationTest {

  @BeforeEach
  void clearProfilingContextIntegration() {
    testProfilingContextIntegration.clear();
  }

  @AfterEach
  void resetProfilingContextIntegration() {
    testProfilingContextIntegration.clear();
  }

  @Test
  @Timeout(30)
  void disabledTaskBlockGateLeavesEpollWaitUninstrumented() throws InterruptedException {
    EpollEventLoopGroup group = new EpollEventLoopGroup(1);
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

      TimeUnit.MILLISECONDS.sleep(500);
    } finally {
      group.shutdownGracefully().await(10, TimeUnit.SECONDS);
    }

    assertEquals(0, testProfilingContextIntegration.getTaskBlockBeginCalls().get());
    assertEquals(0, testProfilingContextIntegration.getTaskBlockEndCalls().get());
  }
}
