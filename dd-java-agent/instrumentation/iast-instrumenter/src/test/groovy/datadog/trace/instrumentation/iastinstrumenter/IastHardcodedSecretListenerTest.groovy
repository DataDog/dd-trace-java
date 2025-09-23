package datadog.trace.instrumentation.iastinstrumenter

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool
import datadog.trace.agent.tooling.iast.IastSecretClassReader
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.HardcodedSecretModule
import net.bytebuddy.description.type.TypeDescription
import org.apache.commons.io.IOUtils

class IastHardcodedSecretListenerTest extends InstrumentationSpecification{


  void 'test onConstantPool'(){
    setup:
    final module = Mock(HardcodedSecretModule)
    InstrumentationBridge.registerIastModule(module)
    final classFile = readClassBytes(clazz)
    final pool = new ConstantPool(classFile)
    final type = Mock(TypeDescription)
    final secretClassReader = Mock(IastSecretClassReader)
    final instance = new IastHardcodedSecretListener(secretClassReader)

    when:
    instance.onConstantPool(type, pool, classFile)

    then:
    expected * secretClassReader.readClass(_ , _ , _ as IastHardcodedSecretListener.ReportSecretConsumer)

    where:
    clazz | expected
    HardcodedSecretTestClass | 1
    HardcodedSecretTestClass2 | 0
  }

  void 'test consumer'(){
    setup:
    final module = Mock(HardcodedSecretModule)
    InstrumentationBridge.registerIastModule(module)
    final clazz = 'clazz'
    final method = 'method'
    final value = 'value'
    final line = 1
    final consumer = new IastHardcodedSecretListener.ReportSecretConsumer(module,clazz)

    when:
    consumer.accept(method, value, line)

    then:
    1 * module.onHardcodedSecret(value, method, clazz, line)
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

  class HardcodedSecretTestClass {

    public static final String FOO = "foo"
    public static final String SECRET = "ghu_39GyMbaIlk2UMGTkC9WCDlpe9AjRNZa1WZQW"
  }

  class HardcodedSecretTestClass2 {

    public static final String FOO = "foo"

    public static final String MORE_THAN_10 = "012345678ABCD"
  }
}

