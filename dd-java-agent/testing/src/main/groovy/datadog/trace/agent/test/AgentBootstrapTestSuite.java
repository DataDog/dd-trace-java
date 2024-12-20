package datadog.trace.agent.test;

import org.junit.platform.suite.api.BeforeSuite;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
//@SelectClasses(names = {"GrpcStreamingV0Test", "GrpcStreamingTest", "datadog.trace.agent.test.AgentTestRunner"})
//@IncludeTags("AgentBootstrap")
@SelectPackages("datadog.trace.instrumentation.jackson216.core")
//@IncludeClassNamePatterns(".*")
public class AgentBootstrapTestSuite {

  @BeforeSuite
  public static void beforeSuite() {
    AgentBootstrapSpockGlobalExtension.init();
  }
}
