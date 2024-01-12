import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool
import datadog.trace.api.config.IastConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.HardcodedSecretModule
import datadog.trace.instrumentation.iastinstrumenter.IastHardcodedSecretListener
import net.bytebuddy.description.type.TypeDescription
import org.apache.commons.io.IOUtils

class IastHardcodedSecretListenerTest extends AgentTestRunner{


  void 'test'(){
    setup:
    injectSysConfig(IastConfig.IAST_HARDCODED_SECRET_ENABLED, enabled)
    final module = Mock(HardcodedSecretModule)
    InstrumentationBridge.registerIastModule(module)
    final classFile = readClassBytes(clazz)
    final pool = new ConstantPool(classFile)
    final type = Mock(TypeDescription)
    final instance = IastHardcodedSecretListener.INSTANCE

    when:
    instance.onConstantPool(type, pool, classFile)

    then:
    expected * module.onStringLiteral(_, _, _)

    where:
    clazz | enabled | expected
    HardcodedSecretTestClass| 'true' | 1
    HardcodedSecretTestClass| 'false' | 0
    HardcodedSecretTestClass2 | 'true' | 0
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
    public static final String LITERAL_LONGER_THAN_10_CHARS = "12345678901"
  }

  class HardcodedSecretTestClass2 {

    public static final String FOO = "foo"
  }
}

