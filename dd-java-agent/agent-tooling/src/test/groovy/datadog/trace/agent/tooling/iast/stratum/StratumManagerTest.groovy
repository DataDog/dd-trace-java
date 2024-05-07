package datadog.trace.agent.tooling.iast.stratum

import datadog.trace.test.util.DDSpecification
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils

class StratumManagerTest extends DDSpecification {

  void 'test StratumManager shouldBeAnalyzed'(){

    when:
    def result = StratumManager.shouldBeAnalyzed(internalClassName)

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

  void 'test StratumManager analyzeClass'(){
    given:
    byte[] data = FileUtils.readFileToByteArray(new File("src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class"))

    when:
    StratumManager.INSTANCE.analyzeClass(data)

    then:
    final result  = StratumManager.INSTANCE.get("org.apache.jsp.register_jsp")
    result != null
    result.getInputLineNumber(216) == 70
    result.getSourceFile() == "register.jsp"
    result
  }
}
