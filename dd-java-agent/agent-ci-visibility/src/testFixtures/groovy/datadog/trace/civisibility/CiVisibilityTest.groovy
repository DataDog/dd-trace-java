package datadog.trace.civisibility

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.config.ModuleExecutionSettings
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.JvmInfoFactory
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory
import datadog.trace.civisibility.coverage.NoopCoverageProbeStore
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.events.BuildEventsHandlerImpl
import datadog.trace.civisibility.events.TestEventsHandlerImpl
import datadog.trace.civisibility.ipc.SignalServer
import datadog.trace.civisibility.source.MethodLinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
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

  private static final List<SkippableTest> skippableTests = new ArrayList<>()

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
    moduleExecutionSettingsFactory.create(_, _) >> {
      Map<String, String> properties = [
        (CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED) : String.valueOf(!skippableTests.isEmpty())
      ]
      return new ModuleExecutionSettings(properties, Collections.singletonMap(dummyModule, skippableTests))
    }

    def coverageProbeStoreFactory = new NoopCoverageProbeStore.NoopCoverageProbeStoreFactory()
    DDTestFrameworkSession.Factory testFrameworkSessionFactory = (String projectName, Path projectRoot, String component, Long startTime) -> {
      def ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]
      TestDecorator testDecorator = new TestDecoratorImpl(component, ciTags)
      return new DDTestFrameworkSessionImpl(
      projectName,
      startTime,
      Config.get(),
      testDecorator,
      sourcePathResolver,
      codeowners,
      methodLinesResolver,
      coverageProbeStoreFactory,
      moduleExecutionSettingsFactory,
      )
    }

    InstrumentationBridge.registerTestEventsHandlerFactory {
      component, path ->
      DDTestFrameworkSession testSession = testFrameworkSessionFactory.startSession(dummyModule, path, component, null)
      DDTestFrameworkModule testModule = testSession.testModuleStart(dummyModule, null)
      new TestEventsHandlerImpl(testSession, testModule)
    }

    DDBuildSystemSession.Factory buildSystemSessionFactory = (String projectName, Path projectRoot, String startCommand, String component, Long startTime) -> {
      def ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]
      TestDecorator testDecorator = new TestDecoratorImpl(component, ciTags)
      TestModuleRegistry testModuleRegistry = new TestModuleRegistry()
      SignalServer signalServer = new SignalServer()
      RepoIndexBuilder repoIndexBuilder = Stub(RepoIndexBuilder)
      return new DDBuildSystemSessionImpl(
      projectName,
      rootPath.toString(),
      startCommand,
      startTime,
      Config.get(),
      testModuleRegistry,
      testDecorator,
      sourcePathResolver,
      codeowners,
      methodLinesResolver,
      moduleExecutionSettingsFactory,
      coverageProbeStoreFactory,
      signalServer,
      repoIndexBuilder
      )
    }

    InstrumentationBridge.registerBuildEventsHandlerFactory {
      decorator -> new BuildEventsHandlerImpl<>(buildSystemSessionFactory, new JvmInfoFactory())
    }

    InstrumentationBridge.registerCoverageProbeStoreRegistry(coverageProbeStoreFactory)
  }

  @Override
  void setup() {
    skippableTests.clear()
  }

  def givenSkippableTests(List<SkippableTest> tests) {
    skippableTests.addAll(tests)
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
  final String testStatus,
  final Map<String, Object> testTags = null,
  final String resource = null,
  final String testCommand = null,
  final String testToolchain = null,
  final Throwable exception = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testSessionId
    trace.span(index) {
      testSessionId = span.getTag(Tags.TEST_SESSION_ID)

      parent()
      operationName expectedOperationPrefix() + ".test_session"
      resourceName resource ? resource : dummyModule
      spanType DDSpanTypes.TEST_SESSION_END
      errored exception != null
      duration({ it > 1L })
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_TEST_SESSION
        "$Tags.TEST_TYPE" TestDecorator.TEST_TYPE
        if (testCommand) {
          "$Tags.TEST_COMMAND" testCommand
        } else {
          // the default command for sessions that run without build system instrumentation
          "$Tags.TEST_COMMAND" dummyModule
        }
        if (testToolchain) {
          "$Tags.TEST_TOOLCHAIN" testToolchain
        }
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
  final Long testSessionId,
  final String testStatus,
  final Map<String, Object> testTags = null,
  final Throwable exception = null,
  final String resource = null) {
    def testFramework = expectedTestFramework()
    def testFrameworkVersion = expectedTestFrameworkVersion()

    def testModuleId
    trace.span(index) {
      testModuleId = span.getTag(Tags.TEST_MODULE_ID)

      parentSpanId(BigInteger.valueOf(testSessionId))
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
  final Long testSessionId,
  final Long testModuleId,
  final String testSuite,
  final String testStatus,
  final Map<String, Object> testTags = null,
  final Throwable exception = null,
  final boolean emptyDuration = false,
  final Collection<String> categories = null,
  final boolean sourceFilePresent = true) {
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
        "$Tags.TEST_SESSION_ID" testSessionId
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
        if (sourceFilePresent) {
          "$Tags.TEST_SOURCE_FILE" DUMMY_SOURCE_PATH
        }

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
  final Long testSessionId,
  final Long testModuleId,
  final Long testSuiteId,
  final String testSuite,
  final String testName,
  final String testMethod,
  final String testStatus,
  final Map<String, Object> testTags = null,
  final Throwable exception = null,
  final boolean emptyDuration = false,
  final Collection<String> categories = null,
  final boolean sourceFilePresent = true,
  final boolean sourceMethodPresent = true) {
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
        "$Tags.TEST_SESSION_ID" testSessionId
        "$Tags.TEST_MODULE_ID" testModuleId
        "$Tags.TEST_SUITE_ID" testSuiteId
        "$Tags.TEST_MODULE" dummyModule
        "$Tags.TEST_SUITE" testSuite
        "$Tags.TEST_NAME" testName
        "$Tags.TEST_FRAMEWORK" testFramework
        "$Tags.TEST_FRAMEWORK_VERSION" testFrameworkVersion
        "$Tags.TEST_STATUS" testStatus
        if (testTags) {
          testTags.each { key, val -> tag(key, val) }
        }

        if (sourceFilePresent) {
          "$Tags.TEST_SOURCE_FILE" DUMMY_SOURCE_PATH
          "$Tags.TEST_CODEOWNERS" Strings.toJson(DUMMY_CODE_OWNERS)
        }

        if (sourceMethodPresent) {
          "$Tags.TEST_SOURCE_METHOD" testMethod
          "$Tags.TEST_SOURCE_START" DUMMY_TEST_METHOD_START
          "$Tags.TEST_SOURCE_END" DUMMY_TEST_METHOD_END
        }

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
