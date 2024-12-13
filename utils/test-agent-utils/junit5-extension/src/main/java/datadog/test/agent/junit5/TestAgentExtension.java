package datadog.test.agent.junit5;

import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.create;

import datadog.test.agent.TestAgentClient;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.function.Predicate;

public class TestAgentExtension implements BeforeEachCallback, BeforeAllCallback, AfterEachCallback, AfterAllCallback, TestInstancePostProcessor {
  private static final Namespace NAMESPACE = create(TestAgentExtension.class);
  private static final String TEST_CONTAINER = "test-container";
  private static final String TEST_AGENT_CLIENT = "test-agent-client";

  private static final String TEST_AGENT_CONTAINER = "ghcr.io/datadog/dd-apm-test-agent/ddapm-test-agent";
  private static final String TEST_AGENT_VERSION = "latest";
  private static final int TEST_AGENT_PORT = 8126;

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    Store store = context.getStore(NAMESPACE);

    GenericContainer<?> container = startTestAgent(context);
    container.start();
    store.put(TEST_CONTAINER, container);

    TestAgentClient client = new TestAgentClient(container.getHost(), container.getMappedPort(TEST_AGENT_PORT));
    store.put(TEST_AGENT_CLIENT, client);
  }



  private void injectTestAgentClient(ExtensionContext context) {


  }

  private GenericContainer<?> startTestAgent(ExtensionContext extensionContext) throws Exception {
//    Path snapshotsFolder = getSnapshotsFolder(extensionContext);

    return new GenericContainer<>(DockerImageName.parse(TEST_AGENT_CONTAINER + ":" + TEST_AGENT_VERSION))
        .withExposedPorts(TEST_AGENT_PORT)
        .withEnv("SNAPSHOT_CI", "0")
        .withCopyFileToContainer(MountableFile.forClasspathResource("/test-agent-snapshots/"), "/snapshots");
  }

//  private Path getSnapshotsFolder(ExtensionContext extensionContext) throws URISyntaxException {
//    ClassLoader testClassLoader = extensionContext.getTestClass().orElseThrow(() -> new IllegalStateException("No test class")).getClassLoader();
//    URL resource = testClassLoader.getResource("test-agent-snaphots");
//    if (resource == null) {
//      throw new IllegalStateException("No test-agent-snaphots resource found");
//    }
//    return Paths.get(resource.toURI());
//  }

  @Override
  public void beforeEach(ExtensionContext context) throws IOException {
    TestAgentClient client = getTestAgentClient(context);
    String token = context.getUniqueId();
    client.startSession(token);
    client.setActiveSessionToken(token);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    String token = context.getUniqueId();
    TestAgentClient client = getTestAgentClient(context);
    client.clearSession(token);
    client.setActiveSessionToken(null);
  }

  @Override
  public void afterAll(ExtensionContext extension) {
    GenericContainer<?> testAgentContainer = extension.getStore(NAMESPACE).remove(TEST_CONTAINER, GenericContainer.class);
    testAgentContainer.close();
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
    TestAgentClient client = getTestAgentClient(context);
    Class<?> testClass = context.getRequiredTestClass();
    Predicate<Field> filter = field -> field.getType() == TestAgentClient.class;
    for (Field field : ReflectionSupport.findFields(testClass, filter, HierarchyTraversalMode.TOP_DOWN)) {
      field.setAccessible(true);
      field.set(testInstance, client);
    }
  }

  private TestAgentClient getTestAgentClient(ExtensionContext context) {
    return context.getStore(NAMESPACE).get(TEST_AGENT_CLIENT, TestAgentClient.class);
  }
}
