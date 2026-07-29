package datadog.trace.bootstrap.config.provider.stableconfig;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleTest {

  @Test
  void testNoArgConstructorYieldsEmptyState() {
    Rule rule = new Rule();
    assertTrue(rule.getSelectors().isEmpty());
    assertTrue(rule.getConfiguration().isEmpty());
  }

  @Test
  void testConstructorExposesValuesViaGetters() {
    Selector selector = new Selector("process_arguments", "-Dfoo", asList("bar"), "equals");
    Map<String, Object> configuration = new HashMap<>();
    configuration.put("DD_SERVICE", "test");

    Rule rule = new Rule(asList(selector), configuration);

    assertEquals(1, rule.getSelectors().size());
    assertSame(selector, rule.getSelectors().get(0));
    assertSame(configuration, rule.getConfiguration());
  }

  @Test
  void testFromParsesValidRule() {
    Map<String, Object> selectorMap = new LinkedHashMap<>();
    selectorMap.put("origin", "process_arguments");
    selectorMap.put("key", "-Dfoo");
    selectorMap.put("matches", asList("bar"));
    selectorMap.put("operator", "equals");

    Map<String, Object> configuration = new LinkedHashMap<>();
    configuration.put("DD_SERVICE", "test");

    Map<String, Object> ruleMap = new LinkedHashMap<>();
    ruleMap.put("selectors", asList(selectorMap));
    ruleMap.put("configuration", configuration);

    Rule rule = Rule.from(ruleMap);

    List<Selector> selectors = rule.getSelectors();
    assertEquals(1, selectors.size());
    Selector selector = selectors.get(0);
    assertEquals("process_arguments", selector.getOrigin());
    assertEquals("-Dfoo", selector.getKey());
    assertEquals(asList("bar"), selector.getMatches());
    assertEquals("equals", selector.getOperator());

    assertEquals("test", rule.getConfiguration().get("DD_SERVICE"));
  }

  @Test
  void testFromSkipsNullSelectorEntries() {
    Map<String, Object> selectorMap = new LinkedHashMap<>();
    selectorMap.put("origin", "process_arguments");
    selectorMap.put("key", "-Dfoo");
    selectorMap.put("matches", asList("bar"));
    selectorMap.put("operator", "equals");

    Map<String, Object> configuration = new LinkedHashMap<>();
    configuration.put("DD_SERVICE", "test");

    Map<String, Object> ruleMap = new LinkedHashMap<>();
    ruleMap.put("selectors", asList(null, selectorMap));
    ruleMap.put("configuration", configuration);

    Rule rule = Rule.from(ruleMap);

    // The null entry is filtered out, leaving only the valid selector.
    assertEquals(1, rule.getSelectors().size());
    assertEquals("process_arguments", rule.getSelectors().get(0).getOrigin());
  }
}
