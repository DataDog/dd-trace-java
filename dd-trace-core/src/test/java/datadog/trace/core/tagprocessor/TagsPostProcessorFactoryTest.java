package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertNotSame;

import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression test for APMS-20492: in a GraalVM/Mandrel native-image build, {@link
 * TagsPostProcessorFactory}'s cached processor chain (including {@link InternalTagsAdder}, which
 * stamps {@code _dd.base_service}) can be built the first time the class is touched, which may
 * happen at native-image build time rather than at real application runtime. At that point {@code
 * Config.get().getServiceName()} resolves to a build-time fallback (e.g. the native-image builder's
 * own process identity), not the real {@code DD_SERVICE} the app is actually run with, and that
 * stale value gets frozen into the resulting binary.
 *
 * <p>{@code TracerInstaller#installGlobalTracer(CoreTracer)} now calls {@link
 * TagsPostProcessorFactory#reset()} once the global tracer is installed on a native-image runtime,
 * forcing the cached chain to rebuild from whatever {@code Config} resolves to at that point (the
 * real runtime environment). This test locks in that {@code reset()} actually rebuilds the chain
 * (observable via object identity), so that fix can't silently regress into a no-op if {@link
 * TagsPostProcessorFactory}'s internals are refactored later.
 */
class TagsPostProcessorFactoryTest extends DDJavaSpecification {

  @AfterEach
  void restoreDefaults() {
    TagsPostProcessorFactory.reset();
  }

  @Test
  void resetRebuildsTheCachedEagerProcessorChain() {
    TagsPostProcessor before = TagsPostProcessorFactory.eagerProcessor();

    TagsPostProcessorFactory.reset();

    TagsPostProcessor after = TagsPostProcessorFactory.eagerProcessor();

    assertNotSame(
        before,
        after,
        "reset() must rebuild the cached eager processor chain (including InternalTagsAdder,"
            + " which stamps _dd.base_service) from the current Config, or the native-image"
            + " base_service staleness fix (APMS-20492) silently regresses into a no-op");
  }

  @Test
  void resetRebuildsTheCachedLazyProcessorChain() {
    TagsPostProcessor before = TagsPostProcessorFactory.lazyProcessor();

    TagsPostProcessorFactory.reset();

    TagsPostProcessor after = TagsPostProcessorFactory.lazyProcessor();

    assertNotSame(
        before,
        after,
        "reset() must also rebuild the cached lazy processor chain from the current Config");
  }
}
