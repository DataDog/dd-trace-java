import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators
import datadog.trace.agent.tooling.bytebuddy.outline.OutlineTypeParser
import datadog.trace.agent.tooling.bytebuddy.outline.FullTypeParser
import datadog.trace.api.config.IastConfig
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import net.bytebuddy.description.type.TypeDescription

class AnonymousClassesForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig(IastConfig.IAST_ANONYMOUS_CLASSES_ENABLED, 'false')
    super.configurePreAgent()
  }

  void 'test IastInstrumentation enabled: #clazz'() {
    given:
    final instrumentation = new IastInstrumentation()
    final locator = ClassFileLocators.classFileLocator(Thread.currentThread().contextClassLoader)
    final bytes = locator.locate(clazz).resolve()
    final matcher = instrumentation.callerType()

    when: 'using a type'
    final type = fullTypeFor(bytes)
    final typeDescriptorMatches = matcher.matches(type)

    then:
    typeDescriptorMatches == expected

    when: 'using the outline parser'
    final outline = outlineFor(bytes)
    final outlineTypeMatches = matcher.matches(outline)

    then:
    outlineTypeMatches == expected

    where:
    clazz                         | expected
    'OuterClass'                  | true
    'OuterClass$1'                | false
    'OuterClass$InnerClass'       | true
    'OuterClass$InnerStaticClass' | true
  }

  protected TypeDescription outlineFor(final byte[] clazz) {
    return new OutlineTypeParser().parse(clazz)
  }

  protected TypeDescription fullTypeFor(final byte[] clazz) {
    return new FullTypeParser().parse(clazz)
  }
}
