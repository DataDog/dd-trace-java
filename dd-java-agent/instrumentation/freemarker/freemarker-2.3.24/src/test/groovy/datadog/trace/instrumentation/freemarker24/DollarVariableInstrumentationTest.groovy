package datadog.trace.instrumentation.freemarker24

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.XssModule
import freemarker.core.HTMLOutputFormat
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

  void 'undefined variable does not report xss'() {
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration()
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.IGNORE_HANDLER)
    final Template template = new Template("test", new StringReader('test ${undefinedVar}'), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    0 * module.onXss(_, _, _)
  }

  void 'markup output without auto-escaping does not report xss'() {
    // Documents the current behavior: markup output is not reported yet (known gap, APPSEC-71792).
    // The test guards against the ClassCastException the helper used to raise here.
    given:
    final module = Mock(XssModule)
    InstrumentationBridge.registerIastModule(module)

    final Configuration cfg = new Configuration(Configuration.VERSION_2_3_24)
    cfg.setOutputFormat(HTMLOutputFormat.INSTANCE)
    final Template template = new Template("test", new StringReader('test <#noautoesc>${name?no_esc}</#noautoesc>'), cfg)
    final TemplateHashModel rootDataModel = new SimpleHash(cfg.getObjectWrapper())
    rootDataModel.put("name", "<script>alert(1)</script>")

    when:
    template.process(rootDataModel, Mock(FileWriter))

    then:
    0 * module.onXss(_, _, _)
  }
}
