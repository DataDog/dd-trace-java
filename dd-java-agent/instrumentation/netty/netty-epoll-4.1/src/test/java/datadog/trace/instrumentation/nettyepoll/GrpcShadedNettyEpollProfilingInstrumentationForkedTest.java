// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.nettyepoll;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.grpc.netty.shaded.io.netty.bootstrap.ServerBootstrap;
import io.grpc.netty.shaded.io.netty.channel.ChannelInitializer;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.socket.SocketChannel;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Proves the {@code namedOneOf(...)} shaded class name actually matches at runtime. */
@EnabledOnOs(OS.LINUX)
@WithConfig(key = PROFILING_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_PRECHECK, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, value = "false")
class GrpcShadedNettyEpollProfilingInstrumentationForkedTest extends AbstractInstrumentationTest {

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
  void shadedNettyEpollEventLoopWaitDispatchesBalancedTaskBlocks() throws InterruptedException {
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

    assertTrue(testProfilingContextIntegration.getTaskBlockBeginCalls().get() > 0);
    assertEquals(
        testProfilingContextIntegration.getTaskBlockBeginCalls().get(),
        testProfilingContextIntegration.getTaskBlockEndCalls().get());
  }
}
