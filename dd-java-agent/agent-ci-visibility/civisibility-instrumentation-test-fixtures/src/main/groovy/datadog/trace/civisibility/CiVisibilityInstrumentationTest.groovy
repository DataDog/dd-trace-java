package datadog.trace.civisibility

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.environment.EnvironmentVariables
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.DDTest
import datadog.trace.api.civisibility.DDTestSuite
import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.config.LibraryCapability
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestMetadata
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge
import datadog.trace.api.civisibility.events.TestEventsHandler
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.civisibility.telemetry.tag.Provider
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.ContextStore
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.*
import datadog.trace.civisibility.coverage.file.FileCoverageStore
import datadog.trace.civisibility.coverage.report.NoOpCoverageProcessor
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.diff.Diff
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.civisibility.domain.BuildSystemSession
import datadog.trace.civisibility.domain.TestFrameworkModule
import datadog.trace.civisibility.domain.TestFrameworkSession
import datadog.trace.civisibility.domain.buildsystem.BuildSystemSessionImpl
import datadog.trace.civisibility.domain.buildsystem.ModuleSignalRouter
import datadog.trace.civisibility.domain.headless.HeadlessTestSession
import datadog.trace.civisibility.events.BuildEventsHandlerImpl
import datadog.trace.civisibility.events.TestEventsHandlerImpl
import datadog.trace.civisibility.ipc.SignalServer
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import datadog.trace.civisibility.source.index.RepoIndexBuilder
import datadog.trace.civisibility.telemetry.CiVisibilityMetricCollectorImpl
import datadog.trace.civisibility.test.ExecutionStrategy
import datadog.trace.civisibility.utils.ConcurrentHashMapContextStore
import datadog.trace.civisibility.writer.ddintake.CiTestCovMapperV2
import datadog.trace.civisibility.writer.ddintake.CiTestCycleMapperV1
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.RemoteMapper
import datadog.trace.core.DDSpan
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.msgpack.jackson.dataformat.MessagePackFactory

import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import java.util.stream.Collectors

abstract class CiVisibilityInstrumentationTest extends InstrumentationSpecification {

  public static final int SLOW_TEST_THRESHOLD_MILLIS = 1000
  public static final int VERY_SLOW_TEST_THRESHOLD_MILLIS = 2000

  static final String DUMMY_CI_TAG = "dummy_ci_tag"
  static final String DUMMY_CI_TAG_VALUE = "dummy_ci_tag_value"
  static final String DUMMY_SOURCE_PATH = "dummy_source_path"
  static final int DUMMY_TEST_METHOD_START = 12
  static final int DUMMY_TEST_METHOD_END = 18
  static final int DUMMY_TEST_CLASS_START = 11
  static final int DUMMY_TEST_CLASS_END = 19
  static final Collection<String> DUMMY_CODE_OWNERS = ["owner1", "owner2"]

  static final AGENT_KEY_FILE = Files.createTempFile("TestFrameworkTest", "dummy_agent_key")

  def cleanupSpec() {
    Files.deleteIfExists(AGENT_KEY_FILE)
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    Files.write(AGENT_KEY_FILE, "dummy".getBytes())

    injectSysConfig(GeneralConfig.API_KEY_FILE, AGENT_KEY_FILE.toString())
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT, "1")
    injectSysConfig(CiVisibilityConfig.TEST_MANAGEMENT_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES, "5")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_TEST_ORDER, CIConstants.FAIL_FAST_TEST_ORDER)
  }

  @SuppressWarnings('UnusedPrivateField')
  private static final class Settings {
    private volatile List<TestIdentifier> skippableTests = []
    private volatile List<TestFQN> flakyTests
    private volatile List<TestFQN> knownTests
    private volatile List<TestFQN> quarantinedTests = []
    private volatile List<TestFQN> disabledTests = []
    private volatile List<TestFQN> attemptToFixTests = []
    private volatile Diff diff = LineDiff.EMPTY

    private volatile boolean itrEnabled
    private volatile boolean flakyRetryEnabled
    private volatile boolean earlyFlakinessDetectionEnabled
    private volatile boolean impactedTestsDetectionEnabled
    private volatile boolean testManagementEnabled
    private volatile boolean failedTestReplayEnabled = false
  }

  private final Settings settings = new Settings()

  @Override
  def setup() {
    TEST_WRITER.setFilter(spanFilter)

    def ciProvider = Provider.GITHUBACTIONS

    def metricCollector = Stub(CiVisibilityMetricCollectorImpl)

    def sourcePathResolver = Stub(SourcePathResolver)
    sourcePathResolver.getSourcePath(_ as Class) >> DUMMY_SOURCE_PATH
    sourcePathResolver.getResourcePath(_ as String) >> {
      String path -> path
    }

    def codeowners = Stub(Codeowners)
    codeowners.getOwners(DUMMY_SOURCE_PATH) >> DUMMY_CODE_OWNERS

    def linesResolver = Stub(LinesResolver)
    linesResolver.getMethodLines(_ as Method) >> new LinesResolver.Lines(DUMMY_TEST_METHOD_START, DUMMY_TEST_METHOD_END)
    linesResolver.getClassLines(_ as Class<?>) >> new LinesResolver.Lines(DUMMY_TEST_CLASS_START, DUMMY_TEST_CLASS_END)

    def executionSettingsFactory = new MockExecutionSettingsFactory(settings)

    def coverageStoreFactory = new FileCoverageStore.Factory(metricCollector, sourcePathResolver)
    TestFrameworkSession.Factory testFrameworkSessionFactory = (String projectName, String component, Long startTime, Collection<LibraryCapability> capabilities) -> {
      def config = Config.get()
      def ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]
      TestDecorator testDecorator = new TestDecoratorImpl(component, "session-name", "test-command", ciTags)
      return new HeadlessTestSession(
      projectName,
      startTime,
      ciProvider,
      config,
      metricCollector,
      testDecorator,
      sourcePathResolver,
      codeowners,
      linesResolver,
      coverageStoreFactory,
      new ExecutionStrategy(
      config,
      executionSettingsFactory.create(JvmInfo.CURRENT_JVM, ""),
      sourcePathResolver,
      linesResolver),
      capabilities
      )
    }

    def currentPath = Paths.get("").toAbsolutePath()
    def rootPath = currentPath.parent
    def moduleName = rootPath.relativize(currentPath)
    InstrumentationBridge.registerTestEventsHandlerFactory(new MockTestEventHandlerFactory(testFrameworkSessionFactory, metricCollector, moduleName))

    BuildSystemSession.Factory buildSystemSessionFactory = (String projectName, Path projectRoot, String startCommand, String component, Long startTime) -> {
      def config = Config.get()
      def ciTags = [(DUMMY_CI_TAG): DUMMY_CI_TAG_VALUE]
      TestDecorator testDecorator = new TestDecoratorImpl(component, "session-name", "test-command", ciTags)
      ModuleSignalRouter moduleSignalRouter = new ModuleSignalRouter()
      SignalServer signalServer = new SignalServer()
      RepoIndexBuilder repoIndexBuilder = Stub(RepoIndexBuilder)
      return new BuildSystemSessionImpl(
      projectName,
      startCommand,
      startTime,
      ciProvider,
      config,
      metricCollector,
      moduleSignalRouter,
      testDecorator,
      sourcePathResolver,
      codeowners,
      linesResolver,
      executionSettingsFactory,
      signalServer,
      repoIndexBuilder,
      new NoOpCoverageProcessor.Factory()
      )
    }

    InstrumentationBridge.registerBuildEventsHandlerFactory {
      decorator -> new BuildEventsHandlerImpl<>(buildSystemSessionFactory, new JvmInfoFactoryImpl())
    }

    CoveragePerTestBridge.registerCoverageStoreRegistry(coverageStoreFactory)
  }

  private static final class MockExecutionSettingsFactory implements ExecutionSettingsFactory {
    private final Settings settings

    MockExecutionSettingsFactory(Settings settings) {
      this.settings = settings
    }

    @Override
    ExecutionSettings create(JvmInfo jvmInfo, String moduleName) {
      def earlyFlakinessDetectionSettings = settings.earlyFlakinessDetectionEnabled
      ? new EarlyFlakeDetectionSettings(true, [
        new ExecutionsByDuration(SLOW_TEST_THRESHOLD_MILLIS, 3),
        new ExecutionsByDuration(VERY_SLOW_TEST_THRESHOLD_MILLIS, 2)
      ], 0)
      : EarlyFlakeDetectionSettings.DEFAULT

      def testManagementSettings = settings.testManagementEnabled
      ? new TestManagementSettings(true, 5)
      : TestManagementSettings.DEFAULT

      Map<TestIdentifier, TestMetadata> skippableTestsWithMetadata = new HashMap<>()
      for (TestIdentifier skippableTest : settings.skippableTests) {
        skippableTestsWithMetadata.put(skippableTest, new TestMetadata(false))
      }

      return new ExecutionSettings(
      settings.itrEnabled,
      false,
      settings.itrEnabled,
      settings.flakyRetryEnabled,
      settings.impactedTestsDetectionEnabled,
      false,
      settings.failedTestReplayEnabled,
      earlyFlakinessDetectionSettings,
      testManagementSettings,
      settings.itrEnabled ? "itrCorrelationId" : null,
      skippableTestsWithMetadata,
      [:],
      settings.flakyTests,
      settings.knownTests,
      settings.quarantinedTests,
      settings.disabledTests,
      settings.attemptToFixTests,
      settings.diff)
    }
  }

  private static final class MockTestEventHandlerFactory implements TestEventsHandler.Factory {
    private final TestFrameworkSession.Factory testFrameworkSessionFactory
    private final CiVisibilityMetricCollector metricCollector
    private final String moduleName

    MockTestEventHandlerFactory(testFrameworkSessionFactory, metricCollector, moduleName) {
      this.testFrameworkSessionFactory = testFrameworkSessionFactory
      this.metricCollector = metricCollector
      this.moduleName = moduleName
    }

    @Override
    <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(String component, ContextStore<SuiteKey, DDTestSuite> suiteStore, ContextStore<TestKey, DDTest> testStore, Collection<LibraryCapability> capabilities) {
      TestFrameworkSession testSession = testFrameworkSessionFactory.startSession(moduleName, component, null, capabilities)
      TestFrameworkModule testModule = testSession.testModuleStart(moduleName, null)
      new TestEventsHandlerImpl(metricCollector, testSession, testModule,
      suiteStore != null ? suiteStore : new ConcurrentHashMapContextStore<>(),
      testStore != null ? testStore : new ConcurrentHashMapContextStore<>())
    }
  }

  @Override
  protected String idGenerationStrategyName() {
    "RANDOM"
  }

  final ListWriter.Filter spanFilter = new ListWriter.Filter() {
    private final Object lock = new Object()
    private Collection<DDSpan> spans = new ArrayList<>()

    @Override
    boolean accept(List<DDSpan> trace) {
      synchronized (lock) {
        spans.addAll(trace)
        lock.notifyAll()
      }
      return true
    }

    @SuppressFBWarnings("UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS")
    boolean waitForSpan(Predicate<DDSpan> predicate, long timeoutMillis) {
      long deadline = System.currentTimeMillis() + timeoutMillis
      synchronized (lock) {
        while (!spans.stream().anyMatch(predicate)) {
          def timeLeft = deadline - System.currentTimeMillis()
          if (timeLeft > 0) {
            lock.wait(timeLeft)
          } else {
            return false
          }
        }
        return true
      }
    }
  }

  def givenSkippableTests(List<TestIdentifier> tests) {
    settings.skippableTests = new ArrayList<>(tests)
    settings.itrEnabled = true
  }

  def givenFlakyTests(List<TestFQN> tests) {
    settings.flakyTests = new ArrayList<>(tests)
  }

  def givenKnownTests(List<TestFQN> tests) {
    settings.knownTests = new ArrayList<>(tests)
  }

  def givenQuarantinedTests(List<TestFQN> tests) {
    settings.quarantinedTests = new ArrayList<>(tests)
    settings.testManagementEnabled = true
  }

  def givenDisabledTests(List<TestFQN> tests) {
    settings.disabledTests = new ArrayList<>(tests)
    settings.testManagementEnabled = true
  }

  def givenAttemptToFixTests(List<TestFQN> tests) {
    settings.attemptToFixTests = new ArrayList<>(tests)
    settings.testManagementEnabled = true
  }

  def givenDiff(Diff diff) {
    settings.diff = diff
  }

  def givenFlakyRetryEnabled(boolean flakyRetryEnabled) {
    settings.flakyRetryEnabled = flakyRetryEnabled
  }

  def givenEarlyFlakinessDetectionEnabled(boolean earlyFlakinessDetectionEnabled) {
    settings.earlyFlakinessDetectionEnabled = earlyFlakinessDetectionEnabled
  }

  def givenImpactedTestsDetectionEnabled(boolean impactedTestsDetectionEnabled) {
    settings.impactedTestsDetectionEnabled = impactedTestsDetectionEnabled
  }

  def assertSpansData(String testcaseName, Map<String, String> replacements = [:], List<String> ignoredTags = []) {
    Predicate<DDSpan> sessionSpan = span -> span.spanType == "test_session_end"
    spanFilter.waitForSpan(sessionSpan, TimeUnit.SECONDS.toMillis(20))

    def traces = TEST_WRITER.toList()

    def events = getEventsAsJson(traces)
    def coverages = getCoveragesAsJson(traces)
    def additionalReplacements = [
      "content.meta.['test.framework_version']": instrumentedLibraryVersion(),
      "content.meta.['test.toolchain']"        : "${instrumentedLibraryName()}:${instrumentedLibraryVersion()}"
    ] + replacements

    def additionalIgnoredTags = CiVisibilityTestUtils.IGNORED_TAGS + ignoredTags

    if (EnvironmentVariables.get("GENERATE_TEST_FIXTURES") != null) {
      return generateTestFixtures(testcaseName, events, coverages, additionalReplacements, additionalIgnoredTags)
    }

    return CiVisibilityTestUtils.assertData(testcaseName, events, coverages, additionalReplacements, additionalIgnoredTags)
  }

  def generateTestFixtures(String testcaseName, List<Map> events, List<Map> coverages, Map<String, String> additionalReplacements, List<String> additionalIgnoredTags) {
    def clazz = this.getClass()
    def resourceName = "/" + clazz.name.replace('.', '/') + ".class"
    def classfilePath = clazz.getResource(resourceName).toURI().schemeSpecificPart
    def searchIndex = classfilePath.indexOf("/build/classes/groovy")
    def modulePath = classfilePath.substring(0, searchIndex)
    def submoduleName = classfilePath.substring(searchIndex + "/build/classes/groovy".length()).split("/")[1]
    if (!Files.exists(Paths.get(modulePath + "/src/" + submoduleName + "/resources/"))) {
      // probably running a "latestDepTest" that uses fixtures from "test"
      submoduleName = "test"
    }
    def baseTemplatesPath = modulePath + "/src/" + submoduleName + "/resources/" + testcaseName
    CiVisibilityTestUtils.generateTemplates(baseTemplatesPath, events, coverages, additionalReplacements.keySet(), additionalIgnoredTags)
    return [:]
  }

  def assertTestsOrder(List<TestFQN> expectedOrder) {
    TEST_WRITER.waitForTraces(expectedOrder.size() + 1)
    def traces = TEST_WRITER.toList()
    def events = getEventsAsJson(traces)
    return CiVisibilityTestUtils.assertTestsOrder(events, expectedOrder)
  }

  def assertCapabilities(Collection<LibraryCapability> capabilities, int expectedTraceCount) {
    ListWriterAssert.assertTraces(TEST_WRITER, expectedTraceCount, true, new CiVisibilityTestUtils.SortTracesByType(), {
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST
          tags(false) {
            arePresent(capabilities.stream().map(LibraryCapability::asTag).collect(Collectors.toList()))
            areNotPresent(LibraryCapability.values().stream().filter(capability -> !capabilities.contains(capability)).map(LibraryCapability::asTag).collect(Collectors.toList()))
          }
        }
      }
    })

    return true
  }

  def test(String suite, String name) {
    return new TestFQN(suite, name)
  }

  def getEventsAsJson(List<List<DDSpan>> traces) {
    return getSpansAsJson(new CiTestCycleMapperV1(Config.get().getCiVisibilityWellKnownTags(), false), traces)
  }

  def getCoveragesAsJson(List<List<DDSpan>> traces) {
    return getSpansAsJson(new CiTestCovMapperV2(false), traces)
  }

  def getSpansAsJson(RemoteMapper mapper, List<List<DDSpan>> traces) {
    def buffer = new GrowableBuffer(8192)
    def writer = new MsgPackWriter(buffer)
    def msgPackMapper = new ObjectMapper(new MessagePackFactory())
    def jsonSpans = []
    for (List<DDSpan> trace : traces) {
      for (DDSpan span : trace) {
        writer.format([span], mapper)

        ByteBuffer slicedBuffer = buffer.slice()
        buffer.reset()

        if (slicedBuffer.remaining() == 0) {
          continue
        }

        byte[] bytes = new byte[slicedBuffer.remaining()]
        slicedBuffer.get(bytes)
        jsonSpans += msgPackMapper.readValue(bytes, Map)
      }
    }
    return jsonSpans
  }

  abstract String instrumentedLibraryName()

  abstract String instrumentedLibraryVersion()

  BitSet lines(int ... setBits) {
    BitSet bitSet = new BitSet()
    for (int bit : setBits) {
      bitSet.set(bit)
    }
    return bitSet
  }
}
