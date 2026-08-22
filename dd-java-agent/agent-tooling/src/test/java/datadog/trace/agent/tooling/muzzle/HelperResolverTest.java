package datadog.trace.agent.tooling.muzzle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.BuildTimeProviderFixture;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.CombineAdvice;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.InferredHelperFixture;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.InferredModule;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.ManualHelperFixture;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.ManualModule;
import datadog.trace.agent.tooling.muzzle.MuzzleGeneratorFixtures.OwnerWithMuzzleFixture;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.Test;

class HelperResolverTest {

  private static final String INFERRED = InferredHelperFixture.class.getName();
  private static final String MANUAL = ManualHelperFixture.class.getName();
  private static final String OWNER = OwnerWithMuzzleFixture.class.getName();
  private static final String MUZZLE_HELPER = OwnerWithMuzzleFixture.MuzzleHelper.class.getName();

  @Test
  void isBuildTimeOnlyDetectsMuzzleReferenceProviders() {
    ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
    // classes that use the muzzle Reference API are build-time only and must not be injected
    assertTrue(HelperResolver.isBuildTimeOnly(BuildTimeProviderFixture.class.getName(), locator));
    assertTrue(HelperResolver.isBuildTimeOnly(MUZZLE_HELPER, locator));
    // ordinary helper is injectable
    assertFalse(HelperResolver.isBuildTimeOnly(INFERRED, locator));
  }

  @Test
  void declaredHelperListIsUsedAsIsWithoutMergingInference() throws Exception {
    List<String> injected = injectedHelpers(new ManualModule());

    // A module that declares helperClassNames() gets exactly that list - nothing inferred from the
    // advice is merged in.
    assertTrue(injected.contains(MANUAL), "manually declared helper should be injected");
    assertFalse(injected.contains(INFERRED), "inferred helper must not be merged into manual list");
    assertFalse(injected.contains(OWNER), "ownOutput helper must not be merged into manual list");
  }

  @Test
  void inferredHelpersAreUsedWhenNoListDeclaredAndMuzzleProvidersDropped() throws Exception {
    List<String> injected = injectedHelpers(new InferredModule());

    // If no declared list, the helpers inferred from the advice are injected minus the
    // build-time-only muzzle provider and advice root.
    assertTrue(injected.contains(INFERRED), "inferred helper should be injected");
    assertTrue(injected.contains(OWNER), "ownOutput helper should be injected");
    assertFalse(injected.contains(MUZZLE_HELPER), "build-time MuzzleHelper must not be injected");
    assertFalse(injected.contains(CombineAdvice.class.getName()), "advice root is not a helper");
    assertFalse(injected.contains(MANUAL), "helper the advice never references is not inferred");
  }

  private static List<String> injectedHelpers(InstrumenterModule module) throws Exception {
    // Point "ownOutput" at this module's compiled test classes so the fixtures count as this
    // subproject's own helpers.
    File sourceRoot = classesRootOf(MuzzleGeneratorFixtures.class);

    ClassLoader loader = HelperResolverTest.class.getClassLoader();
    Map<String, Reference> crawled =
        ReferenceCreator.createReferencesFrom(CombineAdvice.class.getName(), loader);
    List<Reference> references = new ArrayList<>(crawled.values());
    Set<String> adviceClasses = Collections.singleton(CombineAdvice.class.getName());

    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    // computeInjectedHelpers resolves classes via the context class-loader.
    Thread.currentThread().setContextClassLoader(loader);
    try {
      return Arrays.asList(
          HelperResolver.computeInjectedHelpers(module, references, adviceClasses, sourceRoot));
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private static File classesRootOf(Class<?> type) throws Exception {
    return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}
