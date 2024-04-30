package datadog.trace.agent.tooling.iast.stratum

import datadog.trace.test.util.DDSpecification
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
    'foo/bar/Baz2ejsp' | true
    'foo/bar/Baz_tag' | false
    'foo/bar/jsp/Baz_tag' | true
  }

  void 'test StratumManager analyzeClass'(){
    given:
    final clazz = IndexJsp

    when:
    StratumManager.analyzeClass(readClassBytes(clazz))

    then:
    final result  = StratumManager.get(IndexJsp.getSimpleName()) != null
    result == true
  }

  byte [] readClassBytes(Class<?> clazz){
    final String classResourceName = clazz.getName().replace('.', '/') + ".class"
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(classResourceName)) {
      if(is == null) {
        throw new IllegalStateException("Could not find class resource: " + classResourceName)
      }
      return IOUtils.toByteArray(is)
    }
  }
}
