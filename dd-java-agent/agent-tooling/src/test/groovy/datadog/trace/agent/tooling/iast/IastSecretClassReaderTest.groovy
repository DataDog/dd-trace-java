package datadog.trace.agent.tooling.iast

import datadog.trace.api.function.TriConsumer
import datadog.trace.test.util.DDSpecification
import org.apache.commons.io.IOUtils

class IastSecretClassReaderTest extends DDSpecification {

  void 'test'(){
    given:
    final ageSecret = 'age-secret-key'
    final githubSecret = 'github-app-token'
    final clazz = WithHardcodedSecret
    final secrets = [(WithHardcodedSecret.getSecret()): ageSecret, (WithHardcodedSecret.getSecret2()): githubSecret]
    final classFile = readClassBytes(clazz)
    final consumer = Mock(TriConsumer)

    when:
    IastSecretClassReader.INSTANCE.readClass(secrets, classFile, consumer)

    then:
    1 * consumer.accept('getSecret', ageSecret, 9)
    1 * consumer.accept('getSecret2', githubSecret, 13)
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
