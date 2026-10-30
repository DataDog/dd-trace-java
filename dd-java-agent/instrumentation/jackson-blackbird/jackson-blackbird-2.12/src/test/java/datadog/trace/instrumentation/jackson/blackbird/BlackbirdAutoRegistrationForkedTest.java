package datadog.trace.instrumentation.jackson.blackbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** With the agent and the flag on, mappers the app creates get Blackbird and behave the same. */
@WithConfig(
    key = TraceInstrumentationConfig.TRACE_EXPERIMENTAL_JACKSON_BLACKBIRD_ENABLED,
    value = "true")
class BlackbirdAutoRegistrationForkedTest extends AbstractInstrumentationTest {

  private static long blackbirdCount(ObjectMapper m) {
    return m.getRegisteredModuleIds().stream()
        .filter(id -> String.valueOf(id).equals(BlackbirdModule.class.getName()))
        .count();
  }

  /** What an application does: create a mapper, then configure it. */
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
