package datadog.trace.civisibility

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.config.ModuleExecutionSettings
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.coverage.CoverageBridge
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.civisibility.codeowners.Codeowners
import datadog.trace.civisibility.config.JvmInfo
import datadog.trace.civisibility.config.JvmInfoFactoryImpl
import datadog.trace.civisibility.config.ModuleExecutionSettingsFactory
import datadog.trace.civisibility.coverage.SegmentlessTestProbes
import datadog.trace.civisibility.decorator.TestDecorator
import datadog.trace.civisibility.decorator.TestDecoratorImpl
import datadog.trace.civisibility.events.BuildEventsHandlerImpl
import datadog.trace.civisibility.events.TestEventsHandlerImpl
import datadog.trace.civisibility.ipc.SignalServer
import datadog.trace.civisibility.source.MethodLinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import datadog.trace.civisibility.source.index.RepoIndexBuilder
import datadog.trace.civisibility.writer.ddintake.CiTestCovMapperV2
import datadog.trace.civisibility.writer.ddintake.CiTestCycleMapperV1
import datadog.trace.common.writer.RemoteMapper
import datadog.trace.core.DDSpan
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Unroll

import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Unroll
abstract class CiVisibilityInstrumentationTest extends AgentTestRunner {

  static String dummyModule
  static final String DUMMY_CI_TAG = "dummy_ci_tag"
  static final String DUMMY_CI_TAG_VALUE = "dummy_ci_tag_value"
  static final String DUMMY_SOURCE_PATH = "dummy_source_path"
  static final int DUMMY_TEST_METHOD_START = 12
  static final int DUMMY_TEST_METHOD_END = 18
  static final Collection<String> DUMMY_CODE_OWNERS = ["owner1", "owner2"]

  private static Path agentKeyFile

  private static final List<TestIdentifier> skippableTests = new ArrayList<>()
  private static final List<TestIdentifier> flakyTests = new ArrayList<>()
  private static volatile boolean itrEnabled = false
  private static volatile boolean flakyRetryEnabled = false

  def setupSpec() {
    def currentPath = Paths.get("").toAbsolutePath()
    def rootPath = currentPath.parent
    dummyModule = rootPath.relativize(currentPath)

    def sourcePathResolver = Stub(SourcePathResolver)
    sourcePathResolver.getSourcePath(_ as Class) >> DUMMY_SOURCE_PATH
    sourcePathResolver.getResourcePath(_ as String) >> {
      String path -> path
    }

    def codeowners = Stub(Codeowners)
    codeowners.getOwners(DUMMY_SOURCE_PATH) >> DUMMY_CODE_OWNERS

    def methodLinesResolver = Stub(MethodLinesResolver)
    methodLinesResolver.getLines(_ as Method) >> new MethodLinesResolver.MethodLines(DUMMY_TEST_METHOD_START, DUMMY_TEST_METHOD_END)

    def moduleExecutionSettingsFactory = Stub(ModuleExecutionSettingsFactory)
    moduleExecutionSettingsFactory.create(_ as JvmInfo, _ as String) >> {
      Map<String, String> properties = [
        (CiVisibilityConfig.CIVISIBILITY_ITR_ENABLED): String.valueOf(itrEnabled),
        (CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ENABLED): String.valueOf(flakyRetryEnabled)
      ]
      return new ModuleExecutionSettings(false,
      itrEnabled,
      flakyRetryEnabled,
      properties,
      Collections.singletonMap(dummyModule, skippableTests),
      flakyTests,
      Collections.emptyList())
    }

    def coverageProbeStoreFactory = new SegmentlessTestProbes.SegmentlessTestProbesFactory()
    DDTestFrameworkSession.Factory testFrameworkSessionFactory = (String projectName, String component, Long startTime) -> {
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
      moduleExecutionSettingsFactory.create(JvmInfo.CURRENT_JVM, "")
      )
    }

    InstrumentationBridge.registerTestEventsHandlerFactory {
      component ->
      DDTestFrameworkSession testSession = testFrameworkSessionFactory.startSession(dummyModule, component, null)
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
      decorator -> new BuildEventsHandlerImpl<>(buildSystemSessionFactory, new JvmInfoFactoryImpl())
    }

    CoverageBridge.registerCoverageProbeStoreRegistry(coverageProbeStoreFactory)
  }

  @Override
  void setup() {
    skippableTests.clear()
    flakyTests.clear()
    itrEnabled = false
    flakyRetryEnabled = false
  }

  def givenSkippableTests(List<TestIdentifier> tests) {
    skippableTests.addAll(tests)
    itrEnabled = true
  }

  def givenFlakyTests(List<TestIdentifier> tests) {
    flakyTests.addAll(tests)
    flakyRetryEnabled = true
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
  }

  def cleanupSpec() {
    Files.deleteIfExists(agentKeyFile)
  }

  def assertSpansData(String testcaseName, int expectedTracesCount) {
    TEST_WRITER.waitForTraces(expectedTracesCount)
    def traces = TEST_WRITER.toList()

    def events = getEventsAsJson(traces)
    def coverages = getCoveragesAsJson(traces)
    def additionalReplacements = [
      "content.meta.['test.framework_version']": instrumentedLibraryVersion(),
      "content.meta.['test.toolchain']": "${instrumentedLibraryName()}:${instrumentedLibraryVersion()}"
    ]

    // uncomment to generate expected data templates
    //        def baseTemplatesPath = CiVisibilityInstrumentationTest.classLoader
    //          .getResource("test-succeed")
    //          .toURI()
    //          .schemeSpecificPart
    //          .replace('build/resources/test', 'src/test/resources')
    //          .replace('build/resources/latestDepTest', 'src/test/resources')
    //          .replace("test-succeed", testcaseName)
    //        CiVisibilityTestUtils.generateTemplates(baseTemplatesPath, events, coverages, additionalReplacements)

    CiVisibilityTestUtils.assertData(testcaseName, events, coverages, additionalReplacements)
    return true
  }

  def getEventsAsJson(List<List<DDSpan>> traces) {
    return getSpansAsJson(new CiTestCycleMapperV1(Config.get().getWellKnownTags(), false), traces)
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
