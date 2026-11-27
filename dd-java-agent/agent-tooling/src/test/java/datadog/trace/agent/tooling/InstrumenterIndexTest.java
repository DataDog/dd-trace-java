package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.InstrumenterIndex.decodeModuleLazyLoadable;
import static datadog.trace.agent.tooling.InstrumenterIndex.decodeModuleNeedsEarlyLoad;
import static datadog.trace.agent.tooling.InstrumenterIndex.encodeModuleFlags;
import static datadog.trace.agent.tooling.InstrumenterIndex.isLazyLoadable;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
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

  @Test
  void moduleMatchingOnlyKnownTypesIsLazyLoadable() {
    InstrumenterModule module = new TestModule(new SingleTypeMember(), new KnownTypesMember());

    assertTrue(isLazyLoadable(module, false));
    assertTrue(decodeModuleLazyLoadable(encodeModuleFlags(module, false)));
  }

  @Test
  void moduleWithHierarchyMemberIsNotLazyLoadable() {
    InstrumenterModule module = new TestModule(new SingleTypeMember(), new HierarchyMember());

    assertFalse(isLazyLoadable(module, false));
    assertFalse(decodeModuleLazyLoadable(encodeModuleFlags(module, false)));
  }

  @Test
  void moduleWithContextStoreIsNotLazyLoadable() {
    InstrumenterModule module =
        new TestModule(new SingleTypeMember()) {
          @Override
          public Map<String, String> contextStore() {
            return singletonMap("java.lang.Runnable", "java.lang.Object");
          }
        };

    assertFalse(isLazyLoadable(module, false));
  }

  @Test
  void moduleWithAdviceOverridesIsNotLazyLoadable() {
    assertFalse(isLazyLoadable(new TestModule(new SingleTypeMember()), true));
  }

  @Test
  void moduleWithoutMembersIsNotLazyLoadable() {
    assertFalse(isLazyLoadable(new TestModule(), false));
  }

  @Test
  void earlyLoadModuleIsNotLazyLoadable() {
    assertFalse(isLazyLoadable(new TestJpmsOnlyModule(), false));
  }

  static class TestModule extends InstrumenterModule {
    private final List<Instrumenter> members;

    TestModule(Instrumenter... members) {
      super("lazy-test");
      this.members = asList(members);
    }

    @Override
    public List<Instrumenter> typeInstrumentations() {
      return members;
    }
  }

  static class SingleTypeMember implements Instrumenter, Instrumenter.ForSingleType {
    @Override
    public String instrumentedType() {
      return "com.example.Single";
    }
  }

  static class KnownTypesMember implements Instrumenter, Instrumenter.ForKnownTypes {
    @Override
    public String[] knownMatchingTypes() {
      return new String[] {"com.example.A", "com.example.B"};
    }
  }

  static class HierarchyMember implements Instrumenter, Instrumenter.ForTypeHierarchy {
    @Override
    public String hierarchyMarkerType() {
      return null;
    }

    @Override
    public ElementMatcher<TypeDescription> hierarchyMatcher() {
      return ElementMatchers.none();
    }
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
