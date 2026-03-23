package datadog.trace.codecoverage;

import datadog.trace.coverage.CoverageReportUploader;
import datadog.trace.coverage.LcovReportWriter;
import datadog.trace.coverage.LinesCoverage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodeCoverageLcovSender {
  private static final Logger log = LoggerFactory.getLogger(CodeCoverageLcovSender.class);
  private final CoverageReportUploader uploader;

  public CodeCoverageLcovSender(CoverageReportUploader uploader) {
    this.uploader = uploader;
  }

  public void upload(Map<String, LinesCoverage> coverage) {
    String lcov = LcovReportWriter.toString(coverage);
    try {
      uploader.upload(
          "lcov", new ByteArrayInputStream(lcov.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      log.debug("Failed to upload code coverage report", e);
    }
  }
}
