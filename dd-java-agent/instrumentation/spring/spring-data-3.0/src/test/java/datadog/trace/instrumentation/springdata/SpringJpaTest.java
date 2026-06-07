// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import spring.jpa.JpaCustomer;
import spring.jpa.JpaCustomerRepository;
import spring.jpa.JpaPersistenceConfig;

/**
 * Verifies that the spring-data repository instrumentation produces identical {@code
 * repository.operation} spans against Spring Data 3.x (the {@code test} suite) and Spring Data 4.x
 * (the {@code latestDepTest} suite). The instrumentation surface is unchanged from the legacy
 * spring-data-1.8 module; this module only lifts the muzzle range and Java baseline to cover the
 * jakarta / Spring 6+ world.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SpringJpaTest extends AbstractInstrumentationTest {

  private AnnotationConfigApplicationContext context;
  private JpaCustomerRepository repository;

  @BeforeAll
  void setupContext() {
    context = new AnnotationConfigApplicationContext(JpaPersistenceConfig.class);
    repository = context.getBean(JpaCustomerRepository.class);
  }

  @AfterAll
  void closeContext() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void objectMethodIsNotTraced() throws Exception {
    // Object methods (toString) flow through the interceptor but must not create a span.
    writer.clear();
    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      repository.toString();
    } finally {
      scope.close();
      parent.finish();
    }

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    assertEquals(1, trace.size(), "Only the parent span is expected, no repository.operation span");
    assertEquals("parent", trace.get(0).getOperationName().toString());
  }

  @Test
  void findAllProducesRepositorySpan() throws Exception {
    writer.clear();
    assertFalse(repository.findAll().iterator().hasNext());
    assertRepositorySpan(awaitRepositorySpan(), "JpaCustomerRepository.findAll");
  }

  @Test
  void crudLifecycleProducesRepositorySpans() throws Exception {
    // insert
    writer.clear();
    JpaCustomer customer = new JpaCustomer("Bob", "Anonymous");
    repository.save(customer);
    assertNotNull(customer.getId());
    assertRepositorySpan(awaitRepositorySpan(), "JpaCustomerRepository.save");

    // derived query
    writer.clear();
    List<JpaCustomer> found = repository.findByLastName("Anonymous");
    assertEquals(1, found.size());
    assertEquals("Bob", found.get(0).getFirstName());
    assertRepositorySpan(awaitRepositorySpan(), "JpaCustomerRepository.findByLastName");

    // delete
    writer.clear();
    repository.delete(found.get(0));
    assertRepositorySpan(awaitRepositorySpan(), "JpaCustomerRepository.delete");
  }

  private DDSpan awaitRepositorySpan() {
    blockUntilTracesMatch(traces -> findRepositorySpan(traces) != null);
    return findRepositorySpan(writer);
  }

  private static DDSpan findRepositorySpan(List<List<DDSpan>> traces) {
    for (List<DDSpan> trace : traces) {
      for (DDSpan span : trace) {
        if ("repository.operation".contentEquals(span.getOperationName())) {
          return span;
        }
      }
    }
    return null;
  }

  private static void assertRepositorySpan(DDSpan span, String resourceName) {
    assertNotNull(span, "Expected a repository.operation span");
    assertEquals("repository.operation", span.getOperationName().toString());
    assertEquals(resourceName, span.getResourceName().toString());
    assertEquals("spring-data", String.valueOf(span.getTag("component")));
    assertEquals("client", String.valueOf(span.getTag("span.kind")));
    assertFalse(span.isError());
  }
}
