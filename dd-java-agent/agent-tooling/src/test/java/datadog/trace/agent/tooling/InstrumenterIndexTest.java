package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.InstrumenterIndex.decodeModuleNeedsEarlyLoad;
import static datadog.trace.agent.tooling.InstrumenterIndex.encodeModuleFlags;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collection;
import java.util.Iterator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InstrumenterIndexTest extends DDJavaSpecification {

  private static final InstrumenterModule UNKNOWN_INSTRUMENTATION =
      new InstrumenterModule("unknown") {};

  private static final Instrumenter UNKNOWN_TRANSFORMATION = new Instrumenter() {};

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

  @Test
  void indexKeepsTrackOfInstrumentationAndTransformationIds() {
    // when
    InstrumenterIndex index = InstrumenterIndex.buildIndex();

    // then
    assertEquals(4, index.instrumentationCount());
    assertEquals(8, index.transformationCount());

    assertEquals(-1, index.instrumentationId(UNKNOWN_INSTRUMENTATION));
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));

    Iterator<InstrumenterModule> moduleIterator = index.modules().iterator();

    assertTrue(moduleIterator.hasNext());

    // module with order=-100 is applied first
    InstrumenterModule firstModule = moduleIterator.next();
    assertEquals("TestIndexFirstModule", firstModule.getClass().getSimpleName());
    assertEquals(-100, firstModule.order());
    assertEquals(0, index.instrumentationId(firstModule));

    assertTrue(moduleIterator.hasNext());

    // multi-module declares several transformations
    InstrumenterModule multiModule = moduleIterator.next();
    assertEquals("TestIndexMultiModule", multiModule.getClass().getSimpleName());
    assertEquals(1, index.instrumentationId(multiModule));

    Iterator<Instrumenter> multiIterator = multiModule.typeInstrumentations().iterator();
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));
    assertEquals(1, index.transformationId(multiIterator.next()));
    assertEquals(2, index.transformationId(multiIterator.next()));
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));
    assertEquals(3, index.transformationId(multiIterator.next()));
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));
    assertEquals(4, index.transformationId(multiIterator.next()));
    assertEquals(5, index.transformationId(multiIterator.next()));
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));
    assertFalse(multiIterator.hasNext());

    assertEquals(-1, index.instrumentationId(UNKNOWN_INSTRUMENTATION));
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));

    assertTrue(moduleIterator.hasNext());

    // self-module just declares itself as a transformation
    InstrumenterModule selfModule = moduleIterator.next();
    assertEquals("TestIndexSelfModule", selfModule.getClass().getSimpleName());
    assertEquals(2, index.instrumentationId(selfModule));

    Iterator<Instrumenter> selfIterator = selfModule.typeInstrumentations().iterator();
    assertEquals(6, index.transformationId(selfIterator.next()));
    assertFalse(selfIterator.hasNext());

    assertTrue(moduleIterator.hasNext());

    // module with order=100 is applied last
    InstrumenterModule lastModule = moduleIterator.next();
    assertEquals("TestIndexLastModule", lastModule.getClass().getSimpleName());
    assertEquals(100, lastModule.order());
    assertEquals(3, index.instrumentationId(lastModule));

    assertFalse(moduleIterator.hasNext());

    assertEquals(-1, index.instrumentationId(UNKNOWN_INSTRUMENTATION));
    assertEquals(-1, index.transformationId(UNKNOWN_TRANSFORMATION));
  }

  @Test
  void javaModuleOpenProviderOnlyModuleGetsNeedsEarlyLoadFlag() {
    // given
    TestJpmsOnlyModule jpmsOnlyModule = new TestJpmsOnlyModule();

    // when
    byte flags = encodeModuleFlags(jpmsOnlyModule, false);

    // then
    assertTrue(decodeModuleNeedsEarlyLoad(flags));
  }

  static class TestJpmsOnlyModule extends InstrumenterModule implements JavaModuleOpenProvider {
    TestJpmsOnlyModule() {
      super("jpms-test-only");
    }

    @Override
    public Collection<String> triggerClasses() {
      return emptyList();
    }
  }
}
