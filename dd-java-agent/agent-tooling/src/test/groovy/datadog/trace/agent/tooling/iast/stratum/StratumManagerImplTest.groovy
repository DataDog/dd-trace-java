package datadog.trace.agent.tooling.iast.stratum

import datadog.trace.api.config.IastConfig
import datadog.trace.test.util.DDSpecification
import org.apache.commons.io.FileUtils

class StratumManagerImplTest extends DDSpecification {

  void 'test shouldBeAnalyzed'(){

    when:
    def result = StratumManagerImpl.shouldBeAnalyzed(internalClassName)

    then:
    result == expected

    where:
    internalClassName | expected
    'foo/bar/Baz' | false
    'foo/jsp/Baz' | false
    'foo/bar/Baz_jsp' | true
    'foo/bar/jsp_Baz' | true
    'foo/bar/Baz_tag' | false
    'foo/bar/jsp/Baz_tag' | true
  }

  void 'test analyzeClass'(){
    given:
    byte[] data = FileUtils.readFileToByteArray(new File("src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class"))

    when:
    StratumManagerImpl.INSTANCE.analyzeClass(data)

    then:
    final result  = StratumManagerImpl.INSTANCE.get("org.apache.jsp.register_jsp")
    result != null
    final inputLine = result.getInputLine(216)
    inputLine.right == 70
    result.getSourceFile(inputLine.left) == "register.jsp"
    result
  }

  void 'test limit reached'(){
    setup:
    injectSysConfig(IastConfig.IAST_SOURCE_MAPPING_MAX_SIZE, "1")
    byte[] data = FileUtils.readFileToByteArray(new File("src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class"))

    when:
    StratumManagerImpl.INSTANCE.analyzeClass(data)

    then:
    final result  = StratumManagerImpl.INSTANCE.get("org.apache.jsp.register_jsp")
    result != null
    StratumManagerImpl.INSTANCE.map.size() == 1

    when:
    StratumManagerImpl.INSTANCE.analyzeClass(new byte[0])

    then:
    StratumManagerImpl.INSTANCE.map.size() == 1
    StratumManagerImpl.INSTANCE.map.isLimitReached()
  }
}
