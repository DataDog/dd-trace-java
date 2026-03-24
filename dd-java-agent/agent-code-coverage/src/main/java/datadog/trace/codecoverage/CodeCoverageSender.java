package datadog.trace.codecoverage;

import datadog.trace.coverage.CoverageKey;
import datadog.trace.coverage.CoverageReportUploader;
import datadog.trace.coverage.LinesCoverage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodeCoverageSender {
  private static final Logger log = LoggerFactory.getLogger(CodeCoverageSender.class);
  private final CoverageReportUploader uploader;

  public CodeCoverageSender(CoverageReportUploader uploader) {
    this.uploader = uploader;
  }

  public void upload(Map<CoverageKey, LinesCoverage> coverage) {
    try {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      CoverageBinaryEncoder.encode(coverage, buf);
      uploader.upload("ddcov", new ByteArrayInputStream(buf.toByteArray()));
    } catch (IOException e) {
      log.debug("Failed to upload code coverage report", e);
    }
  }
}
