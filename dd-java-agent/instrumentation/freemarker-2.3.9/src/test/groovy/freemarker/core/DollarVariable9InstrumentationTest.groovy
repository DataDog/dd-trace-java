package freemarker.core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import freemarker.template.Configuration
import freemarker.template.SimpleHash
import freemarker.template.Template
import freemarker.template.TemplateHashModel

class DollarVariable9InstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test freemarker process'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    final Template template = new Template("test", new StringReader("test"), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put(stringExpression, expression)
    final Environment environment = new Environment(template, rootDataModel, Mock(FileWriter))
    final dollarVariable = new DollarVariable(expression, escapedExpression)
    dollarVariable.beginLine = 1
    expression.target = new Identifier(stringExpression)
    expression.constantValue = new StringLiteral(stringExpression)
    escapedExpression.target = new Identifier(stringExpression)
    escapedExpression.constantValue = new StringLiteral(stringExpression)

    when:
    dollarVariable.accept(environment)

    then:
    1 * module.onXss(_, _, _)

    where:
    stringExpression | expression                | escapedExpression
    "test"           | new BuiltIn.htmlBI()      | new BuiltIn.htmlBI()
  }
}
