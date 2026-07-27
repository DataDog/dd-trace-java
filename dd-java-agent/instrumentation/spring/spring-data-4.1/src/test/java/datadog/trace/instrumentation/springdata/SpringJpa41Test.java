package datadog.trace.instrumentation.springdata;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import spring.jpa.JpaCustomer;
import spring.jpa.JpaCustomerRepository;
import spring.jpa.JpaPersistenceConfig;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SpringJpa41Test extends AbstractInstrumentationTest {

  private JpaCustomerRepository repo;

  @BeforeAll
  void setupApplicationContext() {
    // Initialize Spring context; this triggers JPA setup and metadata queries.
    AgentSpan setupSpan = startSpan("test", "setup");
    AgentScope setupScope = activateSpan(setupSpan);
    try {
      AnnotationConfigApplicationContext context =
          new AnnotationConfigApplicationContext(JpaPersistenceConfig.class);
      repo = context.getBean(JpaCustomerRepository.class);
    } finally {
      setupScope.close();
      setupSpan.finish();
    }
  }

  @BeforeEach
  void clearTraces() {
    writer.clear();
  }

  @Test
  void findAllCreatesRepositoryOperationSpan() throws Exception {
    repo.findAll();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNotNull(repositorySpan, "Expected a repository.operation span for findAll");

    assertTrue(
        repositorySpan.getResourceName().toString().contains("findAll"),
        "Resource name should contain findAll, got: " + repositorySpan.getResourceName());

    assertRepositorySpan(repositorySpan);

    // Database semantic tags (db.type, db.name, span_type=sql) are set on JDBC child spans,
    // not on the repository.operation span, because Spring Data is a database-agnostic
    // abstraction layer. See the 1.8 sibling module for the established pattern.
    DDSpan jdbcSpan = findChildSpan(allSpans, repositorySpan, "hsqldb");
    assertNotNull(jdbcSpan, "Expected a JDBC child span under the repository span");
    assertJdbcSpan(jdbcSpan, "select");
  }

  @Test
  void saveCreatesRepositoryOperationSpan() throws Exception {
    JpaCustomer customer = new JpaCustomer("Alice", "Smith");

    repo.save(customer);

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNotNull(repositorySpan, "Expected a repository.operation span for save");

    assertTrue(
        repositorySpan.getResourceName().toString().contains("save"),
        "Resource name should contain save, got: " + repositorySpan.getResourceName());

    assertRepositorySpan(repositorySpan);

    assertNotNull(customer.getId(), "Customer should have been assigned an ID after save");

    DDSpan jdbcSpan = findChildSpan(allSpans, repositorySpan, "hsqldb");
    assertNotNull(jdbcSpan, "Expected a JDBC child span for the insert operation");
    assertJdbcSpan(jdbcSpan, "insert");
  }

  @Test
  void deleteCreatesRepositoryOperationSpan() throws Exception {
    JpaCustomer customer = new JpaCustomer("Delete", "Me");
    repo.save(customer);
    assertNotNull(customer.getId());
    writer.clear();

    repo.delete(customer);

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNotNull(repositorySpan, "Expected a repository.operation span for delete");

    assertTrue(
        repositorySpan.getResourceName().toString().contains("delete"),
        "Resource name should contain delete, got: " + repositorySpan.getResourceName());

    assertRepositorySpan(repositorySpan);
  }

  @Test
  void customQueryMethodCreatesSpanWithInterfaceName() throws Exception {
    repo.save(new JpaCustomer("Custom", "Query"));
    writer.clear();

    List<JpaCustomer> results = repo.findByLastName("Query");

    // Verify the query returned the expected data
    assertFalse(results.isEmpty(), "findByLastName should return at least one result");
    assertEquals(
        "Custom", results.get(0).getFirstName(), "Returned customer should match saved data");

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNotNull(repositorySpan, "Expected a repository.operation span for findByLastName");

    // Custom query methods always use the repository interface name in the resource,
    // because the declaring class is the specific repository interface, not CrudRepository.
    assertEquals(
        "JpaCustomerRepository.findByLastName",
        repositorySpan.getResourceName().toString(),
        "Resource name should use the repository interface name for custom query methods");

    assertRepositorySpan(repositorySpan);

    DDSpan jdbcSpan = findChildSpan(allSpans, repositorySpan, "hsqldb");
    assertNotNull(jdbcSpan, "Expected a JDBC child span for findByLastName query");
    assertJdbcSpan(jdbcSpan, "select");
  }

  @Test
  void repositoryOperationSpanIsChildOfParentSpan() throws Exception {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      repo.findAll();
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNotNull(repositorySpan, "Expected a repository.operation span");

    assertEquals(
        parentSpan.getSpanId(),
        repositorySpan.getParentId(),
        "repository.operation span should be a child of the active parent span");
  }

  @Test
  void objectMethodDoesNotCreateSpan() throws Exception {
    AgentSpan parentSpan = startSpan("test", "toString-test");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      repo.toString();
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNull(
        repositorySpan, "No repository.operation span should be created for Object.toString()");

    assertEquals(
        1,
        allSpans.size(),
        "Only the parent test span should exist, no repository span for toString");
  }

  @Test
  void errorInRepositoryOperationSetsErrorTags() throws Exception {
    Exception caughtException = null;
    try {
      repo.findById(null);
    } catch (Exception e) {
      caughtException = e;
    }

    assertNotNull(caughtException, "findById(null) should throw an exception");

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan repositorySpan = findSpanByOperationName(allSpans, "repository.operation");
    assertNotNull(repositorySpan, "Expected a repository.operation span even on error");

    assertTrue(repositorySpan.isError(), "repository.operation span should be marked as errored");
    assertNotNull(
        repositorySpan.getTag("error.type"), "error.type tag should be set on errored span");
    assertNotNull(
        repositorySpan.getTag("error.message"), "error.message tag should be set on errored span");
    assertNotNull(
        repositorySpan.getTag("error.stack"), "error.stack tag should be set on errored span");

    assertEquals("spring-data", String.valueOf(repositorySpan.getTag("component")));
    assertEquals("client", String.valueOf(repositorySpan.getTag("span.kind")));
    assertTrue(repositorySpan.isMeasured());
  }

  // -- Helper methods --

  /**
   * Validates the common attributes of a repository.operation span. Spring Data is a
   * database-agnostic abstraction layer, so the repository span does not carry database semantic
   * tags (db.type, db.name, span_type). Those tags are set on the child JDBC/driver spans by the
   * underlying database instrumentation. This matches the established spring-data-1.8 pattern.
   */
  private void assertRepositorySpan(DDSpan span) {
    assertEquals(
        "repository.operation",
        span.getOperationName().toString(),
        "Operation name should be repository.operation");
    assertNotNull(span.getServiceName(), "Service name should not be null");
    // Spring Data intentionally returns null for spanType — the underlying database driver spans
    // (JDBC, MongoDB, etc.) carry the appropriate span type (e.g. "sql").
    assertNull(
        span.getSpanType(),
        "Repository span type should be null; database span type is set on child JDBC spans");
    assertEquals(
        "spring-data",
        String.valueOf(span.getTag("component")),
        "Component tag should be spring-data");
    assertEquals("client", String.valueOf(span.getTag("span.kind")), "Span kind should be client");
    assertFalse(span.isError(), "Repository span should not be errored");
    assertTrue(span.isMeasured(), "repository.operation span should be measured");
  }

  /**
   * Validates the common attributes of a JDBC child span produced by the underlying database
   * instrumentation. These spans carry the database semantic tags that the repository.operation
   * span intentionally omits (see assertRepositorySpan). Matches the 1.8 reference module
   * assertions.
   */
  private void assertJdbcSpan(DDSpan span, String expectedOperation) {
    assertEquals("sql", span.getSpanType().toString(), "JDBC child span type should be sql");
    assertTrue(span.isMeasured(), "JDBC child span should be measured");
    assertEquals(
        "hsqldb", String.valueOf(span.getTag("db.type")), "JDBC child span should have db.type");
    assertEquals(
        "test41",
        String.valueOf(span.getTag("db.instance")),
        "JDBC child span should have db.instance");
    assertEquals(
        "sa", String.valueOf(span.getTag("db.user")), "JDBC child span should have db.user");
    assertEquals(
        expectedOperation,
        String.valueOf(span.getTag("db.operation")),
        "JDBC child span should have db.operation=" + expectedOperation);
  }

  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  private DDSpan findSpanByOperationName(List<DDSpan> spans, String operationName) {
    for (DDSpan span : spans) {
      if (span.getOperationName().toString().equals(operationName)) {
        return span;
      }
    }
    return null;
  }

  private DDSpan findChildSpan(List<DDSpan> spans, DDSpan parent, String serviceName) {
    for (DDSpan span : spans) {
      if (span.getParentId() == parent.getSpanId() && span.getServiceName().contains(serviceName)) {
        return span;
      }
    }
    return null;
  }
}
