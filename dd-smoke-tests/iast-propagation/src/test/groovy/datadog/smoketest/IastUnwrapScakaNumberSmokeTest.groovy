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
 * <p>Background:
 * Scala's String.format() internally calls unwrapArg() which converts:
 * - scala.math.BigDecimal -> java.math.BigDecimal (via underlying() method)
 * - scala.math.BigInt -> java.math.BigInteger (via underlying() method)
 *
 * <p>Problem:
 * IAST's @CallSite.After interceptor captures arguments AFTER Scala's runtime has already
 * called unwrapArg(), but our instrumentation receives the original Scala types.
 * Without unwrapping, passing scala.math.BigDecimal to String.format("%f") causes
 * IllegalFormatConversionException: f != scala.math.BigDecimal
 *
 * <p>Solution:
 * StringOpsCallSite now calls unwrapScalaNumbers() before passing arguments to onStringFormat,
 * ensuring java.math types are used instead of scala.math types.
 *
 * <p>This test verifies:
 * 1. No IllegalFormatConversionException is thrown (200 response, not 500)
 * 2. No error telemetry is logged (checked via isErrorLog override)
 * 3. Correct formatted output is returned
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
    // Verify no IllegalFormatConversionException occurred
    // Without unwrapScalaNumber: scala.math.BigDecimal -> String.format("%f") -> exception -> 500
    // With unwrapScalaNumber: java.math.BigDecimal -> String.format("%f") -> success -> 200
    response.code() == 200
    responseBodyStr == "Value: 123.456000"

    // Note: Taint propagation is NOT expected in this scenario because:
    // 1. The input String "123.456" is tainted (http.request.parameter)
    // 2. BigDecimal(param) creates a numeric object - IAST does not taint non-String objects
    // 3. format() converts back to String, but the BigDecimal was never tainted, so no taint to propagate
    //
    // This test's purpose is to verify that unwrapScalaNumber prevents IllegalFormatConversionException,
    // not to verify taint propagation through String -> BigDecimal -> String conversions.
    // Error detection happens automatically via isErrorLog() override + assertNoErrorLogs() in cleanupSpec().
  }
}
