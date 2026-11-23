package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.createJarWithClasses;
import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.AgentBootstrap;
import java.io.File;
import jvmbootstraptest.AgentLoadedChecker;
import jvmbootstraptest.MyClassLoaderIsNotBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class AgentLoadedIntoBootstrapTest {

  @Test
  void agentLoadsInWhenSeparateJvmIsLaunched() throws Exception {
    assertEquals(
        0,
        runOnSeparateJvm(
            AgentLoadedChecker.class.getName(), emptyList(), emptyList(), emptyMap(), true));
  }

  @Test
  void agentBootstrapIsLoadedNotFromDdJavaAgentJar() throws Exception {
    // setup
    String mainClassName = MyClassLoaderIsNotBootstrap.class.getName();
    String pathToJar =
        createJarWithClasses(mainClassName, MyClassLoaderIsNotBootstrap.class, AgentBootstrap.class)
            .getPath();
    try {
      assertEquals(
          0,
          runOnSeparateJvm(mainClassName, emptyList(), emptyList(), emptyMap(), pathToJar, true));
    } finally {
      // cleanup
      new File(pathToJar).delete();
    }
  }
}
