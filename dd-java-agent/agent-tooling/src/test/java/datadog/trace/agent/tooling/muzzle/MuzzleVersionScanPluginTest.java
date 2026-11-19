package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin.assertInstrumentationMuzzled;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.HelperClass.NestedHelperClass;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.BaseInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.BasicInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.InvalidMissingHelperInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.InvalidOrderHelperInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.SomeAdvice;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class MuzzleVersionScanPluginTest extends DDJavaSpecification {

  private static StrictnessPolicy previousStrictnessPolicy;

  @BeforeAll
  static void setupSpec() {
    previousStrictnessPolicy = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
  }

  @AfterAll
  static void cleanupSpec() {
    ConfigHelper.get().setConfigInversionStrict(previousStrictnessPolicy);
  }

  @TableTest({
    "scenario                            | assertPass | instCP                                                                                                                                                                                                                                                                                                                                                                                                    | testCP                                                                                                                                                                                                                                             ",
    "empty instrumentation               | true       | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$EmptyInst, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$EmptyInst$Muzzle]                                                                                                                                                                                                                                                 | []                                                                                                                                                                                                                                                 ",
    "missing all references              | false      | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst$Muzzle, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$SomeAdvice]                                                                                                                                                                       | []                                                                                                                                                                                                                                                 ",
    "missing advice reference            | false      | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst$Muzzle, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$SomeAdvice]                                                                                                                                                                       | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceParameter, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceStaticReference]                                                                               ",
    "missing advice static reference     | false      | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst$Muzzle, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$SomeAdvice]                                                                                                                                                                       | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceParameter, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceReference]                                                                                     ",
    "all references present              | true       | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$BasicInst$Muzzle, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$SomeAdvice]                                                                                                                                                                       | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceParameter, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceReference, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceStaticReference]",
    "// Matching applies before helpers are injected                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   ",
    "references only provided as helpers | false      | [datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$HelperInst, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$HelperInst$Muzzle, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$SomeAdvice, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceReference, datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$AdviceStaticReference] | []                                                                                                                                                                                                                                                 ",
    "// FIXME: AdviceParameter and AdviceMethodReturn are not validated                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                "
  })
  void assertInstrumentationMuzzledAdvice(
      boolean assertPass, List<Class<?>> instCP, List<Class<?>> testCP) throws Exception {
    ServiceEnabledClassLoader instrumentationLoader = newAdviceInstrumentationLoader(instCP);
    AddableClassLoader testApplicationLoader =
        new AddableClassLoader(TestInstrumentationClasses.class);
    for (Class<?> clazz : testCP) {
      testApplicationLoader.addClass(clazz);
    }

    assertInstrumentationMuzzled(instrumentationLoader, testApplicationLoader, assertPass, null);
  }

  @Test
  void verifyAdviceMatchFailure() throws IOException {
    List<Class<?>> instCP =
        Arrays.asList(BasicInst.class, BasicInst.Muzzle.class, SomeAdvice.class);
    ServiceEnabledClassLoader instrumentationLoader = newAdviceInstrumentationLoader(instCP);
    AddableClassLoader testApplicationLoader =
        new AddableClassLoader(TestInstrumentationClasses.class);

    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () ->
                assertInstrumentationMuzzled(
                    instrumentationLoader, testApplicationLoader, true, null));
    assertEquals("Instrumentation failed Muzzle validation", exception.getMessage());
  }

  @TableTest({
    "scenario             | inst                                                                                 | muzzle                                                                                      | helpers                                                                                                           ",
    "valid helper order   | datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$ValidHelperInst        | datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$ValidHelperInst$Muzzle        | [datadog.trace.agent.tooling.muzzle.HelperClass, datadog.trace.agent.tooling.muzzle.HelperClass$NestedHelperClass]",
    "invalid helper order | datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$InvalidOrderHelperInst | datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses$InvalidOrderHelperInst$Muzzle | [datadog.trace.agent.tooling.muzzle.HelperClass, datadog.trace.agent.tooling.muzzle.HelperClass$NestedHelperClass]"
  })
  void assertInstrumentationMuzzledHelpers(Class<?> inst, Class<?> muzzle, List<Class<?>> helpers)
      throws Exception {
    ServiceEnabledClassLoader instrumentationLoader =
        newHelperInstrumentationLoader(inst, muzzle, helpers);
    AddableClassLoader testApplicationLoader = new AddableClassLoader();

    assertInstrumentationMuzzled(instrumentationLoader, testApplicationLoader, true, null);
  }

  @Test
  void nestedHelpersFailure() throws IOException {
    ServiceEnabledClassLoader instrumentationLoader =
        newHelperInstrumentationLoader(
            InvalidMissingHelperInst.class,
            InvalidOrderHelperInst.Muzzle.class,
            singletonList(NestedHelperClass.class));
    AddableClassLoader testApplicationLoader = new AddableClassLoader();

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                assertInstrumentationMuzzled(
                    instrumentationLoader, testApplicationLoader, true, null));
    assertEquals(
        "Nested helper "
            + NestedHelperClass.class.getName()
            + " must have the parent class "
            + HelperClass.class.getName()
            + " also defined as a helper",
        exception.getMessage());
  }

  private static ServiceEnabledClassLoader newAdviceInstrumentationLoader(List<Class<?>> instCP)
      throws IOException {
    ServiceEnabledClassLoader instrumentationLoader =
        new ServiceEnabledClassLoader(
            InstrumenterModule.class,
            Instrumenter.HasMethodAdvice.class,
            ElementMatcher.class,
            ReferenceMatcher.class,
            Reference.class,
            ReferenceCreator.class,
            ReferenceCreatorTestSupport.class);
    instrumentationLoader.addClass(TestInstrumentationClasses.class);
    instrumentationLoader.addClass(BaseInst.class);
    for (Class<?> clazz : instCP) {
      instrumentationLoader.addClass(clazz);
    }
    return instrumentationLoader;
  }

  private static ServiceEnabledClassLoader newHelperInstrumentationLoader(
      Class<?> inst, Class<?> muzzle, List<Class<?>> helpers) throws IOException {
    ServiceEnabledClassLoader instrumentationLoader =
        new ServiceEnabledClassLoader(
            InstrumenterModule.class,
            BaseInst.class,
            Instrumenter.HasMethodAdvice.class,
            ElementMatcher.class,
            ReferenceMatcher.class,
            Reference.class,
            ReferenceCreator.class,
            inst,
            muzzle);
    for (Class<?> clazz : helpers) {
      instrumentationLoader.addClass(clazz);
    }
    return instrumentationLoader;
  }
}
