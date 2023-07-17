package datadog.trace.civisibility

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.civisibility.coverage.NoopCoverageProbeStore
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.CIVisibility
import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.source.SourcePathResolver
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.events.BuildEventsHandlerImpl
import datadog.trace.civisibility.events.TestEventsHandlerImpl
import datadog.trace.civisibility.ipc.SignalServer
import datadog.trace.civisibility.source.MethodLinesResolver
import datadog.trace.civisibility.source.index.RepoIndex
import datadog.trace.civisibility.source.index.RepoIndexBuilder
import datadog.trace.core.DDSpan
import datadog.trace.util.Strings
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Unroll
abstract class CiVisibilityTest extends AgentTestRunner {

  protected static final Comparator<List<DDSpan>> SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES = new SortTracesByDescSizeThenByNames()

  static String dummyModule
  static final String DUMMY_CI_TAG = "dummy_ci_tag"
  static final String DUMMY_CI_TAG_VALUE = "dummy_ci_tag_value"
  static final String DUMMY_SOURCE_PATH = "dummy_source_path"
  static final int DUMMY_TEST_METHOD_START = 12
  static final int DUMMY_TEST_METHOD_END = 18
  static final Collection<String> DUMMY_CODE_OWNERS = ["owner1", "owner2"]

  private static Path agentKeyFile

  def setupSpec() {
    def currentPath = Paths.get("").toAbsolutePath()
    def rootPath = currentPath.parent
    dummyModule = rootPath.relativize(currentPath)

    def sourcePathResolver = Stub(SourcePathResolver)
    sourcePathResolver.getSourcePath(_) >> DUMMY_SOURCE_PATH

    def codeowners = Stub(Codeowners)
    codeowners.getOwners(DUMMY_SOURCE_PATH) >> DUMMY_CODE_OWNERS

    def methodLinesResolver = Stub(MethodLinesResolver)
    methodLinesResolver.getLines(_) >> new MethodLinesResolver.MethodLines(DUMMY_TEST_METHOD_START, DUMMY_TEST_METHOD_END)

    def moduleExecutionSettingsFactory = Stub(ModuleExecutionSettingsFactory)

    InstrumentationBridge.registerTestEventsHandlerFactory {
      component, testFramework, testFrameworkVersion, path ->
      def ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]
      def testDecorator = new TestDecoratorImpl(component, testFramework, testFrameworkVersion, ciTags)
      new TestEventsHandlerImpl(dummyModule, Config.get(), testDecorator, sourcePathResolver, codeowners, methodLinesResolver)
    }

    InstrumentationBridge.registerBuildEventsHandlerFactory {
      decorator -> new BuildEventsHandlerImpl<>()
    }

    InstrumentationBridge.registerCoverageProbeStoreFactory(new NoopCoverageProbeStore.NoopCoverageProbeStoreFactory())

    CIVisibility.registerSessionFactory (String projectName, Path projectRoot, String component, Long startTime) -> {
      def ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]
      TestDecorator testDecorator = new TestDecoratorImpl(component, null, null, ciTags)
      TestModuleRegistry testModuleRegistry = new TestModuleRegistry()
      SignalServer signalServer = new SignalServer()
      RepoIndexBuilder repoIndexBuilder = Stub(RepoIndexBuilder)
      return new DDTestSessionImpl(
      projectName,
      startTime,
      Config.get(),
      testModuleRegistry,
      testDecorator,
      sourcePathResolver,
      codeowners,
      methodLinesResolver,
      moduleExecutionSettingsFactory,
      signalServer,
      repoIndexBuilder
      )
    }
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    agentKeyFile = Files.createTempFile("TestFrameworkTest", "dummy_agent_key")
    Files.write(agentKeyFile, "dummy".getBytes())

    injectSysConfig(GeneralConfig.API_KEY_FILE, agentKeyFile.toString())
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, "true")
  }

  def cleanupSpec() {
    Files.deleteIfExists(agentKeyFile)
  }

  Long testSessionSpan(final TraceAssert trace,
  final int index,
  final String sessionName,
  final String testCommand,
  final String testToolchain,
  final String testStatus,
  final Map<String, String> testTags = null,
  final Throwable exception = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testSessionId
    trace.span(index) {
      testSessionId = span.getTag(Tags.TEST_SESSION_ID)

      parent()
      operationName expectedOperationPrefix() + ".test_session"
      resourceName sessionName
      spanType DDSpanTypes.TEST_SESSION_END
      errored exception != null
      duration({ it > 1L })
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST_SESSION
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_COMMAND" testCommand
        "$Tags.TEST_TOOLCHAIN" testToolchain
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        "$DUMMY_CI_TAG" DUMMY_CI_TAG_VALUE

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        "$Tags.TEST_SESSION_ID" Long

        defaultTags()
      }
    }
    return testSessionId
  }

  Long testModuleSpan(final TraceAssert trace,
  final int index,
  final String testStatus,
  final Map<String, String> testTags = null,
  final Throwable exception = null,
  final Long testSessionId = null,
  final String resource = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testModuleId
    trace.span(index) {
      testModuleId = span.getTag(Tags.TEST_MODULE_ID)

      if (testSessionId) {
        parentSpanId(BigInteger.valueOf(testSessionId))
      } else {
        parent()
      }
      operationName expectedOperationPrefix() + ".test_module"
      resourceName resource ? resource : dummyModule
      spanType DDSpanTypes.TEST_MODULE_END
      errored exception != null
      duration({ it > 1L })
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST_MODULE
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_MODULE" resource ? resource : dummyModule
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        "$DUMMY_CI_TAG" DUMMY_CI_TAG_VALUE

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        "$Tags.TEST_MODULE_ID" Long

        if (testSessionId) {
          "$Tags.TEST_SESSION_ID" testSessionId
        }

        defaultTags()
      }
    }
    return testModuleId
  }

  Long testSuiteSpan(final TraceAssert trace,
  final int index,
  final Long testModuleId,
  final String testSuite,
  final String testStatus,
  final Map<String, String> testTags = null,
  final Throwable exception = null,
  final boolean emptyDuration = false,
  final Collection<String> categories = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testSuiteId
    trace.span(index) {
      testSuiteId = span.getTag(Tags.TEST_SUITE_ID)

      parentSpanId(BigInteger.valueOf(testModuleId))
      operationName expectedOperationPrefix() + ".test_suite"
      resourceName testSuite
      spanType DDSpanTypes.TEST_SUITE_END
      errored exception != null
      if (emptyDuration) {
        duration({ it == 1L })
      } else {
        duration({ it > 1L })
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST_SUITE
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        "$Tags.TEST_MODULE_ID" testModuleId
        "$Tags.TEST_MODULE" dummyModule
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }
        "$Tags.TEST_SOURCE_FILE" DUMMY_SOURCE_PATH

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        if (categories) {
          "$Tags.TEST_TRAITS" Strings.toJson(["category": Strings.toJson(categories)], true)
        }

        "$DUMMY_CI_TAG" DUMMY_CI_TAG_VALUE

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        "$Tags.TEST_SUITE_ID" Long

        defaultTags()
      }
    }
    return testSuiteId
  }

  void testSpan(final TraceAssert trace,
  final int index,
  final Long testModuleId,
  final Long testSuiteId,
  final String testSuite,
  final String testName,
  final String testMethod,
  final String testStatus,
  final Map<String, String> testTags = null,
  final Throwable exception = null,
  final boolean emptyDuration = false, final Collection<String> categories = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    trace.span(index) {
      parent()
      operationName expectedOperationPrefix() + ".test"
      resourceName "$testSuite.$testName"
      spanType DDSpanTypes.TEST
      errored exception != null
      if (emptyDuration) {
        duration({ it == 1L })
      } else {
        duration({ it > 1L })
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        if (testModuleId != null) {
          "$Tags.TEST_MODULE_ID" testModuleId
        }
        if (testSuiteId != null) {
          "$Tags.TEST_SUITE_ID" testSuiteId
        }
        "$Tags.TEST_MODULE" dummyModule
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_NAME" testName
        "$Tags.TEST_FRAMEWORK" testFramework
        if (testFrameworkVersion) {
          "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        }
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }
        "$Tags.TEST_SOURCE_FILE" DUMMY_SOURCE_PATH
        "$Tags.TEST_SOURCE_METHOD" testMethod
        "$Tags.TEST_SOURCE_START" DUMMY_TEST_METHOD_START
        "$Tags.TEST_SOURCE_END" DUMMY_TEST_METHOD_END
        "$Tags.TEST_CODEOWNERS" Strings.toJson(DUMMY_CODE_OWNERS)

        if (exception) {
          errorTags(exception.class, exception.message)
        }

        if (categories) {
          "$Tags.TEST_TRAITS" Strings.toJson(["category": Strings.toJson(categories)], true)
        }

        "$DUMMY_CI_TAG" DUMMY_CI_TAG_VALUE

        "$Tags.ENV" String
        "$Tags.OS_VERSION" String
        "$Tags.OS_PLATFORM" String
        "$Tags.OS_ARCHITECTURE" String
        "$Tags.RUNTIME_VENDOR" String
        "$Tags.RUNTIME_NAME" String
        "$Tags.RUNTIME_VERSION" String
        "$DDTags.LIBRARY_VERSION_TAG_KEY" String

        defaultTags()
      }
    }
  }

  String component = component()

  abstract String expectedOperationPrefix()

  abstract String expectedTestFramework()

  abstract String expectedTestFrameworkVersion()

  abstract String component()

  private static class SortTracesByDescSizeThenByNames implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      if (o1.size() != o2.size()) {
        return o2.size() - o1.size()
      }
      return rootSpanTrace(o1) <=> rootSpanTrace(o2)
    }

    String rootSpanTrace(List<DDSpan> trace) {
      assert !trace.isEmpty()
      def rootSpan = trace.get(0).localRootSpan
      return "${rootSpan.serviceName}/${rootSpan.operationName}/${rootSpan.resourceName}"
    }
  }
}
