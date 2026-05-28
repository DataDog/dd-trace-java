package datadog.trace.instrumentation.niochannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores;
import org.junit.jupiter.api.Test;

class NioChannelProfilingInstrumentationTest {

  @Test
  void knownMatchingTypesCoversServerAndSocketChannelImpl() {
    NioChannelProfilingInstrumentation instrumentation = new NioChannelProfilingInstrumentation();

    assertArrayEquals(
        new String[] {"sun.nio.ch.ServerSocketChannelImpl", "sun.nio.ch.SocketChannelImpl"},
        instrumentation.knownMatchingTypes());
  }

  @Test
  void serverSocketChannelImplIsAllowedForBootstrapRetransformation() {
    assertFalse(GlobalIgnores.isIgnored("sun.nio.ch.ServerSocketChannelImpl", false));
  }

  @Test
  void socketChannelImplIsAllowedForBootstrapRetransformation() {
    assertFalse(GlobalIgnores.isIgnored("sun.nio.ch.SocketChannelImpl", false));
  }

  /**
   * Without {@code profiling.ddprof.wall.precheck=true} the instrumentation must stay disabled,
   * matching the activation contract of all other TaskBlock modules (lock-support, thread-sleep,
   * object-wait, synchronized-contention, nio-selector).
   */
  @Test
  void isDisabledByDefaultWithoutWallPrecheck() {
    // No system property set → ConfigProvider returns the default (false) →
    // isEnabled() must return false regardless of the profiler-enabled flag.
    NioChannelProfilingInstrumentation instrumentation = new NioChannelProfilingInstrumentation();
    assertFalse(
        instrumentation.isEnabled(),
        "nio-channel must be off when wallprecheck is false (the default)");
  }
}
