package datadog.trace.agent.tooling.stratum

import datadog.trace.agent.tooling.stratum.StratumManager
import datadog.trace.test.util.DDSpecification
import java.util.function.IntConsumer
import org.apache.commons.io.FileUtils

class StratumManagerTest extends DDSpecification {

  void 'test analyzeClass'(){
    given:
    StratumManager stratumManager = new StratumManager(1000, {})
    byte[] data = FileUtils.readFileToByteArray(new File("src/test/resources/datadog.trace.agent.tooling.stratum/register_jsp.class"))

    when:
    stratumManager.analyzeClass(data)

    then:
    final result  = stratumManager.get("org.apache.jsp.register_jsp")
    result != null
    final inputLine = result.getInputLine(216)
    inputLine.right == 70
    result.getSourceFile(inputLine.left) == "register.jsp"
    result
  }

  void 'test limit reached'(){
    setup:
    def newStratumManager = new StratumManager(1, {})
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
