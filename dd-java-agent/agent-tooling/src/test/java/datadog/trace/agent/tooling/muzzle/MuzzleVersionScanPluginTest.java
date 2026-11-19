package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin.assertInstrumentationMuzzled;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.HelperClass.NestedHelperClass;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.AdviceParameter;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.AdviceReference;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.AdviceStaticReference;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.BaseInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.BasicInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.EmptyInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.HelperInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.InvalidMissingHelperInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.InvalidOrderHelperInst;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.SomeAdvice;
import datadog.trace.agent.tooling.muzzle.TestInstrumentationClasses.ValidHelperInst;
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
import org.tabletest.junit.TypeConverter;

public class MuzzleVersionScanPluginTest extends DDJavaSpecification {

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
    "scenario                            | assertPass | instCP                                                                              | testCP                                                   ",
    "empty instrumentation               | true       | [EmptyInst, EmptyInst.Muzzle]                                                       | []                                                       ",
    "missing all references              | false      | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | []                                                       ",
    "missing advice reference            | false      | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | [AdviceParameter, AdviceStaticReference]                 ",
    "missing advice static reference     | false      | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | [AdviceParameter, AdviceReference]                       ",
    "all references present              | true       | [BasicInst, BasicInst.Muzzle, SomeAdvice]                                           | [AdviceParameter, AdviceReference, AdviceStaticReference]",
    "// Matching applies before helpers are injected                                                                                                                                                   ",
    "references only provided as helpers | false      | [HelperInst, HelperInst.Muzzle, SomeAdvice, AdviceReference, AdviceStaticReference] | []                                                       ",
    "// FIXME: AdviceParameter and AdviceMethodReturn are not validated                                                                                                                                "
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
    "scenario             | inst                   | muzzle                        | helpers                                     ",
    "valid helper order   | ValidHelperInst        | ValidHelperInst.Muzzle        | [HelperClass, HelperClass.NestedHelperClass]",
    "invalid helper order | InvalidOrderHelperInst | InvalidOrderHelperInst.Muzzle | [HelperClass, HelperClass.NestedHelperClass]"
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

  @TypeConverter
  public static Class<?> toClass(String name) {
    switch (name) {
      case "EmptyInst":
        return EmptyInst.class;
      case "EmptyInst.Muzzle":
        return EmptyInst.Muzzle.class;
      case "BasicInst":
        return BasicInst.class;
      case "BasicInst.Muzzle":
        return BasicInst.Muzzle.class;
      case "HelperInst":
        return HelperInst.class;
      case "HelperInst.Muzzle":
        return HelperInst.Muzzle.class;
      case "ValidHelperInst":
        return ValidHelperInst.class;
      case "ValidHelperInst.Muzzle":
        return ValidHelperInst.Muzzle.class;
      case "InvalidOrderHelperInst":
        return InvalidOrderHelperInst.class;
      case "InvalidOrderHelperInst.Muzzle":
        return InvalidOrderHelperInst.Muzzle.class;
      case "SomeAdvice":
        return SomeAdvice.class;
      case "AdviceParameter":
        return AdviceParameter.class;
      case "AdviceReference":
        return AdviceReference.class;
      case "AdviceStaticReference":
        return AdviceStaticReference.class;
      case "HelperClass":
        return HelperClass.class;
      case "HelperClass.NestedHelperClass":
        return NestedHelperClass.class;
      default:
        throw new IllegalArgumentException("Unknown test class: " + name);
    }
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
