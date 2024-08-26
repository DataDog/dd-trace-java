package freemarker.core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import freemarker.template.Configuration
import freemarker.template.SimpleHash
import freemarker.template.Template
import freemarker.template.TemplateHashModel

class DollarVariableInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test freemarker process'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration(Configuration.VERSION_2_3_32)
    final Template template = new Template("test", new StringReader("test"), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put(stringExpression, expression)
    final Environment environment = new Environment(template, rootDataModel, Mock(FileWriter))
    final dollarVariable = new DollarVariable(expression, escapedExpression, new HTMLOutputFormat(), autoEscape)
    dollarVariable.beginLine = 1

    when:
    dollarVariable.accept(environment)

    then:
    1 * module.onXss(_, _, _)

    where:
    stringExpression | expression                | escapedExpression         | outputFormat           | autoEscape
    "test"           | new StringLiteral("test") | new StringLiteral("test") | new HTMLOutputFormat() | false
  }
}
