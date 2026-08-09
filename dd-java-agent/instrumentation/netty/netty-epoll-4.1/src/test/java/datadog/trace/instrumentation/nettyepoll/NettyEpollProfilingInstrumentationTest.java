// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.nettyepoll;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.Instrumenter;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/**
 * Uses {@link MethodDescription.Latent}/{@link TypeDescription.Latent} fixtures named exactly like
 * the real Netty types, rather than loading the real (native-library-backed) {@code EpollEventLoop}
 * classes, so the test runs on any OS/arch.
 */
class NettyEpollProfilingInstrumentationTest {

  private static final String PLAIN = "io.netty.channel.epoll.EpollEventLoop";
  private static final String SHADED = "io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoop";
  private static final String PLAIN_IO_HANDLER = "io.netty.channel.epoll.EpollIoHandler";
  private static final String SHADED_IO_HANDLER =
      "io.grpc.netty.shaded.io.netty.channel.epoll.EpollIoHandler";

  @Test
  void usesItsOwnInstrumentationName() {
    assertEquals("netty-epoll", new NettyEpollProfilingInstrumentation().name());
  }

  @Test
  void matchesPlainAndShadedEpollEventLoopAndEpollIoHandler() {
    assertArrayEquals(
        new String[] {PLAIN, SHADED, PLAIN_IO_HANDLER, SHADED_IO_HANDLER},
        new NettyEpollProfilingInstrumentation().knownMatchingTypes());
  }

  @Test
  void matchesEveryKnownEpollWaitVariantByNameOnly() {
    ElementMatcher<? super MethodDescription> matcher = capturedMatcher();

    for (String name :
        new String[] {
          "epollWait",
          "epollWaitNow",
          "epollWaitNoTimerChange",
          "epollWaitTimeboxed",
          "epollBusyWait"
        }) {
      for (String declaringClass :
          new String[] {PLAIN, SHADED, PLAIN_IO_HANDLER, SHADED_IO_HANDLER}) {
        assertTrue(matcher.matches(methodOf(declaringClass, name)), declaringClass + "#" + name);
      }
    }
  }

  @Test
  void doesNotMatchUnrelatedMethodsOrClasses() {
    ElementMatcher<? super MethodDescription> matcher = capturedMatcher();

    assertFalse(matcher.matches(methodOf(PLAIN, "run")));
    assertFalse(matcher.matches(methodOf("io.netty.channel.epoll.Native", "epollWait")));
  }

  private static ElementMatcher<? super MethodDescription> capturedMatcher() {
    AtomicReference<ElementMatcher<? super MethodDescription>> captured = new AtomicReference<>();
    Instrumenter.MethodTransformer transformer =
        (matcher, adviceClass, additionalAdviceClasses) -> captured.set(matcher);
    new NettyEpollProfilingInstrumentation().methodAdvice(transformer);
    return captured.get();
  }

  private static MethodDescription methodOf(String declaringClass, String name) {
    TypeDescription declaring =
        new TypeDescription.Latent(declaringClass, Modifier.PUBLIC, null, Collections.emptyList());
    return new MethodDescription.Latent(
        declaring,
        name,
        Modifier.PRIVATE,
        Collections.emptyList(),
        TypeDescription.Generic.VOID,
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        null,
        null);
  }
}
