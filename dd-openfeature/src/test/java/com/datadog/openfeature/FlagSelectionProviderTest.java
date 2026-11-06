package com.datadog.openfeature;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.delivery.FlagSelectionTestSupport;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;

/** Checks the provider sends its flag selection on the agentless configuration request. */
class FlagSelectionProviderTest {
  private static final String CONFIG =
      "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
          + "{\"environment\":{\"name\":\"Test\"},\"flags\":{}}}}";

  private LocalHttpServer cdn;
  private final Map<String, String> previousProperties = new HashMap<>();

  @BeforeEach
  void setUp() throws IOException {
    this.cdn = new LocalHttpServer().otherwise(200, CONFIG);
    setProperty("dd.feature.flags.enabled", "true");
    setProperty("dd.feature.flags.configuration.source", "agentless");
    setProperty(
        "dd.feature.flags.configuration.source.agentless.base.url",
        this.cdn.uri("/ufc").toString());
    setProperty("dd.api.key", "");
    FlagSelectionTestSupport.resetReported();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
  }

  @AfterEach
  void tearDown() {
    CapturingServiceProvider.reset();
    FlagSelectionTestSupport.resetReported();
    this.previousProperties.forEach((key, value) -> {
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
    });
    this.cdn.close();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @SuppressWarnings("deprecation")
  void capturesTheSelectionAtConstruction(final boolean alias) throws Exception {
    final List<String> tags = new ArrayList<>(asList(" area:checkout", "mobile"));
    final List<String> keyPatterns = new ArrayList<>(singletonList("checkout.*"));
    final Provider.Options options =
        alias ? new datadog.trace.api.openfeature.Provider.Options() : new Provider.Options();
    options
        .initTimeout(5, SECONDS)
        .flagSelection(new FlagSelection().tags(tags).keyPatterns(keyPatterns));
    final Provider provider =
        alias
            ? new datadog.trace.api.openfeature.Provider(
                (datadog.trace.api.openfeature.Provider.Options) options)
            : new Provider(options);
    tags.add("late");
    keyPatterns.clear();
    options.flagSelection(null);

    assertEquals(
        "selected_flag_tags=area%3Acheckout&selected_flag_tags=mobile"
            + "&selected_flag_key_patterns=checkout.%2A",
        initializeAndGetQuery(provider));
  }

  @Test
  void noSelectionLeavesTheRequestUnchanged() throws Exception {
    final List<Function<Provider.Options, Provider.Options>> withoutValues =
        asList(
            options -> options,
            options -> options.flagSelection(null),
            options -> options.flagSelection(new FlagSelection()),
            options -> options.flagSelection(new FlagSelection().tags(null).keyPatterns(null)),
            options -> options.flagSelection(new FlagSelection().tags(asList(" ", ""))));

    for (final Function<Provider.Options, Provider.Options> configure : withoutValues) {
      final Provider provider =
          new Provider(configure.apply(new Provider.Options().initTimeout(5, SECONDS)));
      assertNull(initializeAndGetQuery(provider));
    }
    assertEquals(withoutValues.size(), this.cdn.requests().size());
    assertEquals(0, messages().size(), messages().toString());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void malformedSelectionLoadsAllTheFlagsWithoutThrowing() throws Exception {
    final List raw = new ArrayList(singletonList("area:checkout"));
    raw.add(7);
    final Provider provider =
        assertDoesNotThrow(() ->
            new Provider(
                new Provider.Options()
                    .initTimeout(5, SECONDS)
                    .flagSelection(new FlagSelection().tags(raw))));

    assertNull(initializeAndGetQuery(provider));
    assertEquals(
        singletonList(
            "ERROR flagSelection was not sent (malformed_selection: flagSelection must be an object"
                + " with optional tags and keyPatterns string lists). Loading the full flag"
                + " configuration."),
        messages());
  }

  @Test
  @SuppressWarnings("deprecation")
  void aliasOptionsKeepTheirTypeWithAFlagSelection() {
    final datadog.trace.api.openfeature.Provider.Options options =
        new datadog.trace.api.openfeature.Provider.Options();

    final datadog.trace.api.openfeature.Provider.Options returned =
        options.flagSelection(new FlagSelection());

    assertSame(options, returned);
  }

  /** Initializes the provider, returns the raw query of its first request and shuts it down. */
  private String initializeAndGetQuery(final Provider provider) throws Exception {
    final int before = this.cdn.requests().size();
    try {
      provider.initialize(null);
    } finally {
      provider.shutdown();
    }
    return this.cdn.requests().get(before).uri.getRawQuery();
  }

  private void setProperty(final String key, final String value) {
    this.previousProperties.put(key, System.setProperty(key, value));
  }

  private static List<String> messages() {
    return CapturingServiceProvider.events().stream()
        .filter(e -> e.message.startsWith("flagSelection"))
        .map(e -> e.level + " " + e.message)
        .collect(Collectors.toList());
  }
}
