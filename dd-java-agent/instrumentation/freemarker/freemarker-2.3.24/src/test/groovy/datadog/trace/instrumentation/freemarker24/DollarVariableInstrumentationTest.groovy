package datadog.trace.instrumentation.freemarker24

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

  void 'plain interpolation reports xss'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    final Template template = new Template("test", new StringReader('test ${name}'), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put("name", "<script>alert(1)</script>")

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    1 * module.onXss(_, _, _)
  }

  void 'non-escaping built-in still reports xss'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    final Template template = new Template("test", new StringReader("test \${name?$builtIn}"), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put("name", "<script>alert(1)</script>")

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    1 * module.onXss(_, _, _)

    where:
    builtIn << ['upper_case', 'js_string', 'j_string']
  }

  void 'html/xml escaping built-ins do not report xss'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    final Template template = new Template("test", new StringReader("test \${name?$builtIn}"), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put("name", "<script>alert(1)</script>")

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    0 * module.onXss(_, _, _)

    where:
    builtIn << ['html', 'xml', 'xhtml']
  }
}
