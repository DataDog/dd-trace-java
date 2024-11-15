package datadog.trace.civisibility

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import datadog.trace.api.civisibility.DDTest
import datadog.trace.api.civisibility.DDTestSuite
import datadog.trace.api.civisibility.InstrumentationBridge
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
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings
import datadog.trace.civisibility.config.ExecutionSettings
import datadog.trace.civisibility.config.ExecutionSettingsFactory
import datadog.trace.civisibility.config.JvmInfo
import datadog.trace.civisibility.config.JvmInfoFactoryImpl
import datadog.trace.civisibility.coverage.file.FileCoverageStore
import datadog.trace.civisibility.coverage.percentage.NoOpCoverageCalculator
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.decorator.TestDecoratorImpl
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
import datadog.trace.common.writer.RemoteMapper
import datadog.trace.core.DDSpan
import org.msgpack.jackson.dataformat.MessagePackFactory

import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

abstract class CiVisibilityInstrumentationTest extends AgentTestRunner {

  static String dummyModule
  static final String DUMMY_CI_TAG = "dummy_ci_tag"
  static final String DUMMY_CI_TAG_VALUE = "dummy_ci_tag_value"
  static final String DUMMY_SOURCE_PATH = "dummy_source_path"
  static final int DUMMY_TEST_METHOD_START = 12
  static final int DUMMY_TEST_METHOD_END = 18
  static final int DUMMY_TEST_CLASS_START = 11
  static final int DUMMY_TEST_CLASS_END = 19
  static final Collection<String> DUMMY_CODE_OWNERS = ["owner1", "owner2"]

  private static Path agentKeyFile

  private static final List<TestIdentifier> skippableTests = new ArrayList<>()
  private static final List<TestIdentifier> flakyTests = new ArrayList<>()
  private static final List<TestIdentifier> knownTests = new ArrayList<>()
  private static volatile boolean itrEnabled = false
  private static volatile boolean flakyRetryEnabled = false
  private static volatile boolean earlyFlakinessDetectionEnabled = false
  public static final int SLOW_TEST_THRESHOLD_MILLIS = 1000
  public static final int VERY_SLOW_TEST_THRESHOLD_MILLIS = 2000

  def setupSpec() {
    def currentPath = Paths.get("").toAbsolutePath()
    def rootPath = currentPath.parent
    dummyModule = rootPath.relativize(currentPath)

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

    def executionSettingsFactory = new ExecutionSettingsFactory() {
      @Override
      ExecutionSettings create(JvmInfo jvmInfo, String moduleName) {
        def earlyFlakinessDetectionSettings = earlyFlakinessDetectionEnabled
        ? new EarlyFlakeDetectionSettings(true, [
          new EarlyFlakeDetectionSettings.ExecutionsByDuration(SLOW_TEST_THRESHOLD_MILLIS, 3),
          new EarlyFlakeDetectionSettings.ExecutionsByDuration(VERY_SLOW_TEST_THRESHOLD_MILLIS, 2)
        ], 0)
        : EarlyFlakeDetectionSettings.DEFAULT

        Map<TestIdentifier, TestMetadata> skippableTestsWithMetadata = new HashMap<>()
        for (TestIdentifier skippableTest : skippableTests) {
          skippableTestsWithMetadata.put(skippableTest, new TestMetadata(false))
        }

        return new ExecutionSettings(
        itrEnabled,
        false,
        itrEnabled,
        flakyRetryEnabled,
        earlyFlakinessDetectionSettings,
        itrEnabled ? "itrCorrelationId" : null,
        skippableTestsWithMetadata,
        [:],
        flakyTests,
        earlyFlakinessDetectionEnabled ? knownTests : null)
      }
    }

    def coverageStoreFactory = new FileCoverageStore.Factory(metricCollector, sourcePathResolver)
    TestFrameworkSession.Factory testFrameworkSessionFactory = (String projectName, String component, Long startTime) -> {
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
      new ExecutionStrategy(config, executionSettingsFactory.create(JvmInfo.CURRENT_JVM, ""))
      )
    }

    InstrumentationBridge.registerTestEventsHandlerFactory(new TestEventHandlerFactory(testFrameworkSessionFactory, metricCollector))

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
      new NoOpCoverageCalculator.Factory()
      )
    }

    InstrumentationBridge.registerBuildEventsHandlerFactory {
      decorator -> new BuildEventsHandlerImpl<>(buildSystemSessionFactory, new JvmInfoFactoryImpl())
    }

    CoveragePerTestBridge.registerCoverageStoreRegistry(coverageStoreFactory)
  }

  private static final class TestEventHandlerFactory implements TestEventsHandler.Factory {
    private final TestFrameworkSession.Factory testFrameworkSessionFactory
    private final CiVisibilityMetricCollector metricCollector

    TestEventHandlerFactory(testFrameworkSessionFactory, metricCollector) {
      this.testFrameworkSessionFactory = testFrameworkSessionFactory
      this.metricCollector = metricCollector
    }

    @Override
    <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(String component, ContextStore<SuiteKey, DDTestSuite> suiteStore, ContextStore<TestKey, DDTest> testStore) {
      TestFrameworkSession testSession = testFrameworkSessionFactory.startSession(dummyModule, component, null)
      TestFrameworkModule testModule = testSession.testModuleStart(dummyModule, null)
      new TestEventsHandlerImpl(metricCollector, testSession, testModule,
      suiteStore != null ? suiteStore : new ConcurrentHashMapContextStore<>(),
      testStore != null ? testStore : new ConcurrentHashMapContextStore<>())
    }
  }

  @Override
  protected String idGenerationStrategyName() {
    "RANDOM"
  }

  @Override
  void setup() {
    skippableTests.clear()
    flakyTests.clear()
    knownTests.clear()
    itrEnabled = false
    flakyRetryEnabled = false
    earlyFlakinessDetectionEnabled = false
  }

  def givenSkippableTests(List<TestIdentifier> tests) {
    skippableTests.addAll(tests)
    itrEnabled = true
  }

  def givenFlakyTests(List<TestIdentifier> tests) {
    flakyTests.addAll(tests)
    flakyRetryEnabled = true
  }

  def givenKnownTests(List<TestIdentifier> tests) {
    knownTests.addAll(tests)
    earlyFlakinessDetectionEnabled = true
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    agentKeyFile = Files.createTempFile("TestFrameworkTest", "dummy_agent_key")
    Files.write(agentKeyFile, "dummy".getBytes())

    injectSysConfig(GeneralConfig.API_KEY_FILE, agentKeyFile.toString())
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ENABLED, "true")
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT, "1")
  }

  def cleanupSpec() {
    Files.deleteIfExists(agentKeyFile)
  }

  def assertSpansData(String testcaseName, int expectedTracesCount, Map<String, String> replacements = [:]) {
    TEST_WRITER.waitForTraces(expectedTracesCount)
    def traces = TEST_WRITER.toList()

    def events = getEventsAsJson(traces)
    def coverages = getCoveragesAsJson(traces)
    def additionalReplacements = [
      "content.meta.['test.framework_version']": instrumentedLibraryVersion(),
      "content.meta.['test.toolchain']"        : "${instrumentedLibraryName()}:${instrumentedLibraryVersion()}"
    ] + replacements

    // uncomment to generate expected data templates
    //    def baseTemplatesPath = CiVisibilityInstrumentationTest.classLoader
    //      .getResource("test-succeed")
    //      .toURI()
    //      .schemeSpecificPart
    //      .replace('build/resources/test', 'src/test/resources')
    //      .replace('build/resources/latestDepTest', 'src/test/resources')
    //      .replace("test-succeed", testcaseName)
    //    CiVisibilityTestUtils.generateTemplates(baseTemplatesPath, events, coverages, additionalReplacements)
    //    return [:]

    return CiVisibilityTestUtils.assertData(testcaseName, events, coverages, additionalReplacements)
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
}
