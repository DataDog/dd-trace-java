package datadog.telemetry.dependency;

import static datadog.telemetry.dependency.DependencyTestHelper.getJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyResolverQueueTest {

  private DependencyResolverQueue resolverQueue = new DependencyResolverQueue();

  @Test
  void resolveSetOfDependencies() {
    resolverQueue.queueURI(getJar("junit-4.12.jar").toURI());
    resolverQueue.queueURI(getJar("asm-util-9.2.jar").toURI());
    resolverQueue.queueURI(getJar("bson-4.2.0.jar").toURI());

    Dependency dep = resolverQueue.pollDependency().get(0);
    assertNotNull(dep);
    assertEquals("junit", dep.name);
    assertEquals("4.12", dep.version);
    assertEquals("junit-4.12.jar", dep.source);
    assertEquals("4376590587C49AC6DA6935564233F36B092412AE", dep.hash);

    dep = resolverQueue.pollDependency().get(0);
    assertNotNull(dep);
    assertEquals("asm-util", dep.name);
    assertEquals("9.2", dep.version);
    assertEquals("asm-util-9.2.jar", dep.source);
    assertEquals("9A5AEC2CB852B8BD20DAF5D2CE9174891267FE27", dep.hash);

    dep = resolverQueue.pollDependency().get(0);
    assertNotNull(dep);
    assertEquals("org.mongodb:bson", dep.name);
    assertEquals("4.2.0", dep.version);
    assertEquals("bson-4.2.0.jar", dep.source);
    assertEquals("F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A", dep.hash);

    List<Dependency> deps = resolverQueue.pollDependency();
    assertTrue(deps.isEmpty());

    // a repeated dependency is added: it has no effect
    resolverQueue.queueURI(getJar("junit-4.12.jar").toURI());
    deps = resolverQueue.pollDependency();
    assertTrue(deps.isEmpty());
  }

  @Test
  void resolveSetOfDependenciesWithQueueLimit() {
    resolverQueue = new DependencyResolverQueue(3);
    resolverQueue.queueURI(getJar("junit-4.12.jar").toURI());
    resolverQueue.queueURI(getJar("asm-util-9.2.jar").toURI());
    resolverQueue.queueURI(getJar("bson-4.2.0.jar").toURI());
    // this dependency should be dropped
    resolverQueue.queueURI(getJar("commons-logging-1.2.jar").toURI());

    Dependency dep = resolverQueue.pollDependency().get(0);
    assertNotNull(dep);
    assertEquals("junit", dep.name);
    assertEquals("4.12", dep.version);
    assertEquals("junit-4.12.jar", dep.source);
    assertEquals("4376590587C49AC6DA6935564233F36B092412AE", dep.hash);

    dep = resolverQueue.pollDependency().get(0);
    assertNotNull(dep);
    assertEquals("asm-util", dep.name);
    assertEquals("9.2", dep.version);
    assertEquals("asm-util-9.2.jar", dep.source);
    assertEquals("9A5AEC2CB852B8BD20DAF5D2CE9174891267FE27", dep.hash);

    dep = resolverQueue.pollDependency().get(0);
    assertNotNull(dep);
    assertEquals("org.mongodb:bson", dep.name);
    assertEquals("4.2.0", dep.version);
    assertEquals("bson-4.2.0.jar", dep.source);
    assertEquals("F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A", dep.hash);

    List<Dependency> deps = resolverQueue.pollDependency();
    assertTrue(deps.isEmpty());
  }
}
