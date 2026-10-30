package datadog.trace.instrumentation.jackson.blackbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@WithConfig(
    key = TraceInstrumentationConfig.TRACE_EXPERIMENTAL_JACKSON_BLACKBIRD_ENABLED,
    value = "true")
class BlackbirdAutoRegistrationForkedTest extends AbstractInstrumentationTest {

  @BeforeEach
  void resetCap() {
    BlackbirdRegistrar.resetForTests();
  }

  private static long blackbirdCount(ObjectMapper m) {
    return m.getRegisteredModuleIds().stream()
        .filter(id -> String.valueOf(id).equals(BlackbirdModule.class.getName()))
        .count();
  }

  private static ObjectMapper appMapper(Corpus.Setup setup) {
    final ObjectMapper m = new ObjectMapper();
    setup.configure.accept(m);
    return m;
  }

  @Test
  void plainConstructorGetsBlackbird() {
    assertEquals(1, blackbirdCount(new ObjectMapper()));
  }

  @Test
  void jsonMapperAndBuilderGetBlackbird() {
    assertEquals(1, blackbirdCount(new JsonMapper()));
    assertEquals(1, blackbirdCount(JsonMapper.builder().addModule(new JavaTimeModule()).build()));
  }

  @Test
  void copiesKeepBlackbird() {
    assertEquals(1, blackbirdCount(new ObjectMapper().copy()));
    assertEquals(1, blackbirdCount(JsonMapper.builder().build().copy()));
  }

  @Test
  void subclassesAreLeftAlone() {
    assertEquals(0, blackbirdCount(new Differential.PlainObjectMapper()));
    assertEquals(0, blackbirdCount(new ObjectMapper() {}));
  }

  @Test
  void registrationsAreCappedPerClassLoader() throws Exception {
    for (int i = 0; i < BlackbirdRegistrar.MAX_REGISTRATIONS; i++) {
      assertEquals(1, blackbirdCount(new ObjectMapper()), "mapper " + i);
    }
    final ObjectMapper overCap = new ObjectMapper();
    assertEquals(0, blackbirdCount(overCap));
    final Corpus.Case c = Corpus.cases().get(0);
    final ObjectMapper baseline = new Differential.PlainObjectMapper();
    assertEquals(baseline.writeValueAsString(c.value), overCap.writeValueAsString(c.value));
  }

  @Test
  void failedRegistrationDisablesLaterMappers() {
    final Module broken =
        new BlackbirdModule() {
          @Override
          public void setupModule(SetupContext context) {
            throw new LinkageError("skewed databind");
          }
        };
    BlackbirdRegistrar.tryRegister(new Differential.PlainObjectMapper(), broken);
    assertEquals(0, blackbirdCount(new ObjectMapper()));
    assertEquals(0, blackbirdCount(new JsonMapper()));
  }

  @Test
  void appRegisteringBlackbirdItselfIsDeduplicated() {
    final ObjectMapper m = new ObjectMapper().registerModule(new BlackbirdModule());
    assertEquals(1, blackbirdCount(m));
  }

  @Test
  void appRegisteringAfterburnerLaterStillRoundTrips() throws Exception {
    final Corpus.Setup setup = Corpus.setups().get(0);
    final ObjectMapper m = appMapper(setup).registerModule(new AfterburnerModule());
    assertTrue(BlackbirdRegistrar.hasAccessorModule(m));
    for (Corpus.Case c : Corpus.cases()) {
      Differential.assertSameOutput(Differential.plain(setup), m, c);
    }
  }

  @Test
  void autoRegisteredMapperUsesGeneratedAccessors() throws Exception {
    Differential.assertBlackbirdAccessorsInUse(appMapper(Corpus.setups().get(0)));
  }

  @Test
  void mapperUsedBeforeConfigurationStillWorks() throws Exception {
    final ObjectMapper m = new ObjectMapper();
    final ObjectMapper baseline = new Differential.PlainObjectMapper();
    final Corpus.SmallBean bean = Corpus.SmallBean.sample(3);
    assertEquals(baseline.writeValueAsString(bean), m.writeValueAsString(bean));
    final Corpus.Setup setup = Corpus.setups().get(1);
    setup.configure.accept(m);
    setup.configure.accept(baseline);
    for (Corpus.Case c : Corpus.cases()) {
      Differential.assertSameOutput(baseline, m, c);
    }
    assertFalse(BlackbirdRegistrar.hasAccessorModule(baseline));
  }

  @ParameterizedTest(name = "{0} / {1}")
  @MethodSource("datadog.trace.instrumentation.jackson.blackbird.Differential#setupsAndCases")
  void sameBytesAndValues(Corpus.Setup setup, Corpus.Case c) throws Exception {
    final ObjectMapper auto = appMapper(setup);
    assertEquals(1, blackbirdCount(auto));
    Differential.assertSameOutput(Differential.plain(setup), auto, c);
  }

  @ParameterizedTest(name = "{0} / {1}")
  @MethodSource("datadog.trace.instrumentation.jackson.blackbird.Differential#setupsAndInputs")
  void sameReadsAndErrors(Corpus.Setup setup, Corpus.Input in) {
    Differential.assertSameRead(Differential.plain(setup), appMapper(setup), in);
  }
}
