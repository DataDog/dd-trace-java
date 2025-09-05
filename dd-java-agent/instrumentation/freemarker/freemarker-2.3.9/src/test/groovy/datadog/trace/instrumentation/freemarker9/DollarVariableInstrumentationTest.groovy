package datadog.trace.instrumentation.freemarker9

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import freemarker.template.Configuration
import freemarker.template.SimpleHash
import freemarker.template.Template
import freemarker.template.TemplateHashModel

class DollarVariableInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test freemarker process'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    final Template template = new Template("test", new StringReader("test \${$stringExpression}"), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put(stringExpression, expression)

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    1 * module.onXss(_, _, _)

    where:
    stringExpression | expression
    "test"           | "test"
  }
}
