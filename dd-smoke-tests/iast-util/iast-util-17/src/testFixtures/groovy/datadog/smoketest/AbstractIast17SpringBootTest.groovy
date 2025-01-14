package datadog.smoketest

import com.github.javaparser.utils.StringEscapeUtils
import okhttp3.FormBody
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

abstract class AbstractIast17SpringBootTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(iastJvmOpts())
    command.addAll((String[]) ['-jar', springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  protected List<String> iastJvmOpts() {
    return [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
    ]
  }

  void 'test String translateEscapes'() {
    setup:
    final url = "http://localhost:${httpPort}/string/translateEscapes"
    final body = new FormBody.Builder()
      .add('parameter', value)
      .build()
    final request = new Request.Builder().url(url).post(body).build()


    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == expected
    }

    where:
    value             | expected
    "withEscape\ttab" | "withEscape" + Character.toString((char)9) + "tab"
    "withEscape\nnewline" | "withEscape" + StringEscapeUtils.unescapeJava("\\u000A")+ "newline"
    "withEscape\bbackline" | "withEscape" + StringEscapeUtils.unescapeJava("\\u0008")+ "backline"
  }
}
