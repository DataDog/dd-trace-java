package datadog.openfeature.internal.core;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationContextTest {

  @Test
  void copiesAttributesAndUsesTargetingKeyAsDefaultId() {
    final Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("role", "admin");
    final EvaluationContext context = new EvaluationContext("subject", attributes);
    attributes.put("role", "changed");

    assertEquals("subject", context.targetingKey());
    assertEquals("subject", context.attribute("id"));
    assertEquals("admin", context.attribute("role"));
  }

  @Test
  void preservesExplicitId() {
    final EvaluationContext context =
        new EvaluationContext("subject", singletonMap("id", "explicit-subject"));

    assertEquals("explicit-subject", context.attribute("id"));
  }

  @Test
  void delegatesLazyAttributeLookup() {
    final EvaluationContext context =
        EvaluationContext.lazy(
            "subject",
            new EvaluationContext.AttributeProvider() {
              @Override
              public boolean contains(final String name) {
                return "id".equals(name);
              }

              @Override
              public Object get(final String name) {
                return "id".equals(name) ? "lazy-subject" : null;
              }
            });

    assertEquals("lazy-subject", context.attribute("id"));
    assertNull(context.attribute("missing"));
  }

  @Test
  void rejectsMissingLazyProvider() {
    assertThrows(IllegalArgumentException.class, () -> EvaluationContext.lazy("subject", null));
  }
}
