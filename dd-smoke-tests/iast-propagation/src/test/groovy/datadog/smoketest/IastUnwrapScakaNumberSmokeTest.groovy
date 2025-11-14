package datadog.smoketest

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

import okhttp3.Request

/**
 * Smoke test to verify that StringOpsCallSite.unwrapScalaNumbers() correctly unwraps
 * Scala ScalaNumber types (BigDecimal, BigInt) to their underlying Java representations
 * before passing them to IAST's onStringFormat.
 *
 * <p>Why:
 * IAST's @CallSite.After interceptor captures arguments AFTER Scala's runtime has already
 * called unwrapArg(), but our instrumentation receives the original Scala types.
 * Without unwrapping, passing scala.math.BigDecimal to String.format("%f") causes
 * IllegalFormatConversionException: f != scala.math.BigDecimal
 */
class IastUnwrapScakaNumberSmokeTest extends AbstractIastServerSmokeTest {

  @Override
  boolean isErrorLog(String log) {
    // Detect the specific telemetry error that would occur if unwrapScalaNumber didn't work.
    // This exact message is logged by StringModuleImpl.formatValue() when String.format()
    // throws IllegalFormatConversionException due to type mismatch.
    return super.isErrorLog(log) ||
      log.contains("Format conversion failed for placeholder %f with parameter type scala.math.BigDecimal: f != scala.math.BigDecimal")
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    final jarPath = System.getProperty('datadog.smoketest.springboot.shadowJar.path')
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      '-jar',
      jarPath,
      "--server.port=${httpPort}"
    ])
    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  void 'test scala formatBigDecimal with unwrapScalaNumber'() {
    setup:
    String url = "http://localhost:${httpPort}/scala/formatBigDecimal?param=123.456"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    response.code() == 200
    responseBodyStr == "Value: 123.456000"
  }
}
