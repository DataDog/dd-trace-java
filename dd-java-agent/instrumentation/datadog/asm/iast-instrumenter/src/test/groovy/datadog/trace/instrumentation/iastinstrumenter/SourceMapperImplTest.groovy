package datadog.trace.instrumentation.iastinstrumenter

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.tooling.stratum.Stratum
import datadog.trace.agent.tooling.stratum.StratumManager
import datadog.trace.api.Pair

class SourceMapperImplTest extends InstrumentationSpecification {

  void 'test is disabled by default'(){
    when:
    def instance = SourceMapperImpl.INSTANCE

    then:
    instance == null
  }

  void 'test getFileAndLine'(){
    setup:
    final stratumManager = Mock(StratumManager)
    final stratum = Mock(Stratum)
    final sourceMapper = new SourceMapperImpl(stratumManager)

    when:
    def result = sourceMapper.getFileAndLine("foo/bar/Baz", 42)

    then: "no stratum for this class"
    1 * stratumManager.get("foo/bar/Baz") >> null
    result == null

    when:
    result = sourceMapper.getFileAndLine("foo/bar/Baz", 42)

    then: "stratum exists but could not get input line number from stratum"
    1 * stratumManager.get("foo/bar/Baz") >> stratum
    1 * stratum.getInputLine(_) >> null
    result == null

    when:
    result = sourceMapper.getFileAndLine("foo/bar/Baz", 42)

    then: "stratum exists and input line number is found"
    1 * stratumManager.get("foo/bar/Baz") >> stratum
    1 * stratum.getInputLine(_) >> new Pair<>(1, 52)
    1 * stratum.getSourceFile(1) >> "foo/bar/Baz.jsp"
    result.getLeft() == "foo/bar/Baz.jsp"
    result.getRight() == 52
  }
}
