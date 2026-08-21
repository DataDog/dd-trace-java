// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.nioselect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import java.lang.reflect.Modifier;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

class NioSelectCallSiteTest {

  @Test
  void usesTheSharedWallclockInstrumentationName() {
    assertEquals("wallclock", new NioSelectProfilingInstrumentation().name());
  }

  @Test
  void registersBothSelectOverloads() {
    Advices advices = NioSelectProfilingInstrumentation.createAdvices();

    assertNotNull(advices.findAdvice("java/nio/channels/Selector", "select", "()I"));
    assertNotNull(advices.findAdvice("java/nio/channels/Selector", "select", "(J)I"));
  }

  @Test
  void callSiteProviderIsAccessibleAcrossAgentClassLoaders() {
    Class<?> provider = NioSelectProfilingInstrumentation.NioSelectCallSites.class;

    assertTrue(Modifier.isPublic(provider.getModifiers()));
    assertTrue(Modifier.isStatic(provider.getModifiers()));
  }

  @Test
  void callerTypeMatchesOnlyNettyEventLoops() {
    ElementMatcher<TypeDescription> matcher = new NioSelectProfilingInstrumentation().callerType();

    assertTrue(matcher.matches(named("io.netty.channel.nio.NioEventLoop")));
    assertTrue(matcher.matches(named("io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoop")));
    assertTrue(matcher.matches(named("io.netty.channel.nio.NioIoHandler")));
    assertTrue(matcher.matches(named("io.grpc.netty.shaded.io.netty.channel.nio.NioIoHandler")));
    assertFalse(matcher.matches(named("com.example.MyApp")));
    assertFalse(matcher.matches(named("io.netty.channel.epoll.EpollEventLoop")));
  }

  private static TypeDescription named(String name) {
    return new TypeDescription.Latent(
        name, Modifier.PUBLIC, null, java.util.Collections.emptyList());
  }
}
