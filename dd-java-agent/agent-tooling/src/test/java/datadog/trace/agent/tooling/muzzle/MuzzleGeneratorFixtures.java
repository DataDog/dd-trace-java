package datadog.trace.agent.tooling.muzzle;

import java.util.Collections;
import net.bytebuddy.pool.TypePool;

/** Fixtures for {@link MuzzleGeneratorTest}. */
final class MuzzleGeneratorFixtures {
  private MuzzleGeneratorFixtures() {}

  /** Helper referenced by the advice. */
  static class InferredHelperFixture {}

  /**
   * Helper the advice does not reference; reachable only via manual definition in {@code
   * helperClassNames()}.
   */
  static class ManualHelperFixture {}

  /** Helper whose nested build-time {@code MuzzleHelper} must not be injected. */
  static class OwnerWithMuzzleFixture {
    static final class MuzzleHelper implements ReferenceProvider {
      @Override
      public Iterable<Reference> buildReferences(TypePool typePool) {
        return Collections.emptyList();
      }
    }
  }

  /** Build-time muzzle provider that must never be injected into the application. */
  static final class BuildTimeProviderFixture implements ReferenceProvider {
    @Override
    public Iterable<Reference> buildReferences(TypePool typePool) {
      return Collections.emptyList();
    }
  }

  /** Advice referencing the inferred helper and the owner but not the manual helper. */
  static class CombineAdvice {
    static void apply() {
      new InferredHelperFixture();
      new OwnerWithMuzzleFixture();
    }
  }

  /** Module declaring a manual helper the advice crawl cannot see. */
  static class CombineModule extends TestInstrumentationClasses.BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {ManualHelperFixture.class.getName()};
    }
  }
}
