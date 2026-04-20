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

  void 'getTemplate returning default #templateType template'() {
    expect:
    new String(BlockingActionHelper.getTemplate(templateType), StandardCharsets.UTF_8)
      .contains(expectedContent)

    where:
    templateType | expectedContent
    HTML         | "<title>You've been blocked</title>"
    JSON         | '"You\'ve been blocked"'
  }

  void 'getTemplate returning custom #templateType template'() {
    setup:
    File tempDir = File.createTempDir('testTempDir-', '')
    Config config = Mock(Config)
    File tempFile = new File(tempDir, fileName)
    tempFile << templateContent

    when:
    BlockingActionHelper.reset(config)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> (templateType == HTML ? tempFile.toString() : null)
    1 * config.getAppSecHttpBlockedTemplateJson() >> (templateType == JSON ? tempFile.toString() : null)
    new String(BlockingActionHelper.getTemplate(templateType), StandardCharsets.UTF_8)
      .contains(templateContent)

    cleanup:
    BlockingActionHelper.reset(Config.get())
    tempDir.deleteDir()

    where:
    templateType | fileName         | templateContent
    HTML         | 'template.html'  | '<body>My template</body>'
    JSON         | 'template.json'  | '{"foo":"bar"}'
  }

  void 'getTemplate with null argument'() {
    expect:
    BlockingActionHelper.getTemplate(null) == null
  }

  void 'will use default #templateType template if #reason'() {
    setup:
    Config config = Mock(Config)
    File tempDir = tempDirSetup ? File.createTempDir('testTempDir-', '') : null
    File template = tempFile ? new File(tempDir, 'template') : null
    if (template) {
      template << 'a' * (500 * 1024 + 1)
    }

    when:
    BlockingActionHelper.reset(config)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> htmlConfigValue?.call(template)
    1 * config.getAppSecHttpBlockedTemplateJson() >> jsonConfigValue?.call(template)
    new String(BlockingActionHelper.getTemplate(templateType), StandardCharsets.UTF_8)
      .contains(expectedContent)

    cleanup:
    BlockingActionHelper.reset(Config.get())
    if (tempDir) {
      tempDir.deleteDir()
    }

    where:
    templateType | reason                       | tempDirSetup | tempFile | htmlConfigValue            | jsonConfigValue            | expectedContent
    HTML         | 'custom file does not exist' | false        | false    | { _ -> '/bad/file.html' }  | { _ -> '/bad/file.json' }  | "<title>You've been blocked</title>"
    JSON         | 'custom file does not exist' | false        | false    | { _ -> '/bad/file.html' }  | { _ -> '/bad/file.json' }  | '"You\'ve been blocked'
    HTML         | 'custom file is too big'     | true         | true     | { it -> it.toString() }    | { it -> it.toString() }    | "<title>You've been blocked</title>"
    JSON         | 'custom file is too big'     | true         | true     | { it -> it.toString() }    | { it -> it.toString() }    | '"You\'ve been blocked'
  }


  void 'getTemplate with security_response_id replaces placeholder in #templateType template'() {
    given:
    def securityResponseId = '12345678-1234-1234-1234-123456789abc'

    when:
    def template = BlockingActionHelper.getTemplate(templateType, securityResponseId)
    def templateStr = new String(template, StandardCharsets.UTF_8)

    then:
    !templateStr.contains('[security_response_id]')
    templateStr.contains(expectedContent.replace('[id]', securityResponseId))

    where:
    templateType | expectedContent
    HTML         | 'Security Response ID: [id]'
    JSON         | '"security_response_id":"[id]"'
  }

  void 'getTemplate without security_response_id uses empty string in #templateType template'() {
    when:
    def template = BlockingActionHelper.getTemplate(templateType, null)
    def templateStr = new String(template, StandardCharsets.UTF_8)

    then:
    !templateStr.contains('[security_response_id]')
    expectedContents.every { content -> templateStr.contains(content) }

    where:
    templateType | expectedContents
    HTML         | ['Security Response ID:']
    JSON         | ['"security_response_id"', '""']
  }

  void 'getTemplate with empty security_response_id uses empty string'() {
    when:
    def htmlTemplate = BlockingActionHelper.getTemplate(HTML, '')
    def jsonTemplate = BlockingActionHelper.getTemplate(JSON, '')

    then:
    !new String(htmlTemplate, StandardCharsets.UTF_8).contains('[security_response_id]')
    !new String(jsonTemplate, StandardCharsets.UTF_8).contains('[security_response_id]')
  }

  void 'getTemplate with security_response_id works with custom #templateType template'() {
    setup:
    File tempDir = File.createTempDir('testTempDir-', '')
    Config config = Mock(Config)
    File tempFile = new File(tempDir, fileName)
    tempFile << templateContent
    def securityResponseId = 'test-block-id-123'

    when:
    BlockingActionHelper.reset(config)
    def template = BlockingActionHelper.getTemplate(templateType, securityResponseId)
    def templateStr = new String(template, StandardCharsets.UTF_8)

    then:
    1 * config.getAppSecHttpBlockedTemplateHtml() >> (templateType == HTML ? tempFile.toString() : null)
    1 * config.getAppSecHttpBlockedTemplateJson() >> (templateType == JSON ? tempFile.toString() : null)
    templateStr.contains(expectedContent.replace('[id]', securityResponseId))
    !templateStr.contains('[security_response_id]')

    cleanup:
    BlockingActionHelper.reset(Config.get())
    tempDir.deleteDir()

    where:
    templateType | fileName         | templateContent                                                       | expectedContent
    HTML         | 'template.html'  | '<body>Custom template with security_response_id: [security_response_id]</body>' | 'Custom template with security_response_id: [id]'
    JSON         | 'template.json'  | '{"error":"blocked","id":"[security_response_id]"}'                   | '"error":"blocked","id":"[id]"'
  }
}
