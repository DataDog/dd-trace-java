package datadog.trace.bootstrap.blocking

import datadog.appsec.api.blocking.BlockingContentType
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import java.nio.charset.StandardCharsets

import static datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType.HTML
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType.JSON

class BlockingActionHelperSpecification extends DDSpecification {

  void 'determineTemplate with auto returns #templateType for "#accept"'() {
    expect:
    BlockingActionHelper.determineTemplateType(BlockingContentType.AUTO, accept) == templateType

    where:
    accept   | templateType
    null        | JSON
    ''          | JSON
    '*/*'       | JSON
    'text/html' | HTML
    'text/html;charset=utf-8' | HTML
    'text/html;charset=iso-8859-1' | HTML
    'application/json, ' | JSON
    'application/json; q=0.9,text/html; q=0.9' | JSON
    'application/json; q=0.9,text/html; q=0.91' | HTML
    'application/json;q=0.7, application/*;q=0.9, text/*;q=0.8' | HTML
    'application/*;q=0.6, application/json;q=0.81 text/*;level=2; q=0.8,' | JSON
    '*/*;q=0.8, text/*;q=0.9' | HTML
    '*/*;q=0.8, text/*;q=0.9, text/html ; q=0.7' | JSON
    'aaaa,text/html' | JSON // gives up after error
  }

  void 'determineTemplate with json'() {
    expect:
    BlockingActionHelper.determineTemplateType(BlockingContentType.JSON, 'text/html') == JSON
  }

  void 'determineTemplate with html'() {
    expect:
    BlockingActionHelper.determineTemplateType(BlockingContentType.HTML, 'application/json') == HTML
  }

  void 'getHttpCode return #result for #input'() {
    expect:
    BlockingActionHelper.getHttpCode(input) == result

    where:
    input | result
    0     | 403
    199   | 403
    600   | 403
    200   | 200
    404   | 404
  }

  void 'getContentType returns #result for #input'() {
    expect:
    BlockingActionHelper.getContentType(input) == result

    where:
    input | result
    JSON  | 'application/json'
    HTML  | 'text/html;charset=utf-8'
    null  | null
  }

  void 'getTemplate returning default html template'() {
    expect:
    new String(BlockingActionHelper.getTemplate(HTML), StandardCharsets.UTF_8)
      .contains("<title>You've been blocked</title>")
  }

  void 'getTemplate returning default JSON template'() {
    expect:
    new String(BlockingActionHelper.getTemplate(JSON), StandardCharsets.UTF_8)
      .contains('"You\'ve been blocked"')
  }

  void 'getTemplate returning custom html template'() {
    setup:
    File tempDir = File.createTempDir('testTempDir-', '')
    Config config = Mock(Config)
    File tempFile = new File(tempDir, 'template.html')
    tempFile << '<body>My template</body>'

    when:
    BlockingActionHelper.reset(config)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> tempFile.toString()
    1 * config.getAppSecHttpBlockedTemplateJson() >> null
    new String(BlockingActionHelper.getTemplate(HTML), StandardCharsets.UTF_8)
      .contains('<body>My template</body>')

    cleanup:
    BlockingActionHelper.reset(Config.get())
    tempDir.deleteDir()
  }

  void 'getTemplate returning custom json template'() {
    setup:
    File tempDir = File.createTempDir('testTempDir-', '')
    Config config = Mock(Config)
    File tempFile = new File(tempDir, 'template.json')
    tempFile << '{"foo":"bar"}'

    when:
    BlockingActionHelper.reset(config)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> null
    1 * config.getAppSecHttpBlockedTemplateJson() >> tempFile.toString()
    new String(BlockingActionHelper.getTemplate(JSON), StandardCharsets.UTF_8)
      .contains('{"foo":"bar"}')

    cleanup:
    BlockingActionHelper.reset(Config.get())
    tempDir.deleteDir()
  }

  void 'getTemplate with null argument'() {
    expect:
    BlockingActionHelper.getTemplate(null) == null
  }

  void 'will use default templates if custom files do not exist'() {
    setup:
    Config config = Mock(Config)

    when:
    BlockingActionHelper.reset(config)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> '/bad/file.html'
    1 * config.getAppSecHttpBlockedTemplateJson() >> '/bad/file.json'
    new String(BlockingActionHelper.getTemplate(HTML), StandardCharsets.UTF_8)
      .contains("<title>You've been blocked</title>")
    new String(BlockingActionHelper.getTemplate(JSON), StandardCharsets.UTF_8)
      .contains('"You\'ve been blocked')

    cleanup:
    BlockingActionHelper.reset(Config.get())
  }

  void 'will use default templates if custom files are too big'() {
    setup:
    Config config = Mock(Config)
    File tempDir = File.createTempDir('testTempDir-', '')
    File template = new File(tempDir, 'template')
    template << 'a' * (500 * 1024 + 1)

    when:
    BlockingActionHelper.reset(config)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> template.toString()
    1 * config.getAppSecHttpBlockedTemplateJson() >> template.toString()
    new String(BlockingActionHelper.getTemplate(HTML), StandardCharsets.UTF_8)
      .contains("<title>You've been blocked</title>")
    new String(BlockingActionHelper.getTemplate(JSON), StandardCharsets.UTF_8)
      .contains('"You\'ve been blocked')

    cleanup:
    BlockingActionHelper.reset(Config.get())
    tempDir.deleteDir()
  }
}
