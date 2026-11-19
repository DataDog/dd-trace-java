package datadog.trace.instrumentation.freemarker9

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import freemarker.template.Configuration
import freemarker.template.SimpleHash
import freemarker.template.Template
import freemarker.template.TemplateExceptionHandler
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

  void 'test freemarker process with undefined variable'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.IGNORE_HANDLER)
    final Template template = new Template("test", new StringReader("test \${undefinedVar}"), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    0 * module.onXss(_, _, _)
  }

  void 'test freemarker process with non top-level expression'() {
    // Documents the current behavior: only root variables are resolved, so ${user.name} is not
    // reported yet (known gap, APPSEC-71792). The test guards against the NPE the helper used to
    // raise here.
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    final Template template = new Template("test", new StringReader("test \${user.name}"), cfg)
    final SimpleHash user = new SimpleHash(cfg.getObjectWrapper())
    user.put("name", "test")
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put("user", user)

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    0 * module.onXss(_, _, _)
  }
}
