package datadog.trace.agent.tooling.stratum

import datadog.trace.agent.tooling.stratum.StratumManager
import datadog.trace.test.util.DDSpecification
import org.apache.commons.io.FileUtils

class StratumManagerTest extends DDSpecification {

  void 'test shouldBeAnalyzed'(){

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

  void 'test analyzeClass'(){
    given:
    byte[] data = FileUtils.readFileToByteArray(new File("src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class"))

    when:
    StratumManager.INSTANCE.analyzeClass(data)

    then:
    final result  = StratumManager.INSTANCE.get("org.apache.jsp.register_jsp")
    result != null
    final inputLine = result.getInputLine(216)
    inputLine.right == 70
    result.getSourceFile(inputLine.left) == "register.jsp"
    result
  }

  void 'test limit reached'(){
    setup:
    def newStratumManager = new StratumManager(1)
    byte[] data = FileUtils.readFileToByteArray(new File("src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class"))

    when:
    newStratumManager.analyzeClass(data)

    then:
    final result  =newStratumManager.get("org.apache.jsp.register_jsp")
    result != null
    newStratumManager.map.size() == 1
    newStratumManager.map.isLimitReached()

    when:
    newStratumManager.analyzeClass(new byte[0])

    then:
    newStratumManager.map.size() == 1
    newStratumManager.map.isLimitReached()
  }
}
