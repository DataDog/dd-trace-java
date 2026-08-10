package com.datadog.iast.model.json;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.VulnerabilityMarks;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

class EvidenceAdapterTest {

  @Test
  void repeatedSensitiveLiteralsRedactToTheSamePattern() throws Exception {
    assumeTrue(Config.get().isIastRedactionEnabled(), "redaction must be enabled");

    // The two 'abc' string literals are detected as sensitive ranges within the same tainted value.
    // Both must map to the first occurrence of "abc" in the source (index 8 -> "ijk"), so they
    // render with an identical pattern. This is the behavior preserved by the chunk -> offset
    // memoization in EvidenceAdapter#addValuePart.
    final String sql = "select 'abc' or 'abc'";
    final Source source = new Source(SourceTypes.REQUEST_PARAMETER_VALUE, "query", sql);
    final Range range = new Range(0, sql.length(), source, VulnerabilityMarks.NOT_MARKED);
    final Evidence evidence = new Evidence(sql, new Range[] {range});
    final Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.SQL_INJECTION,
            Location.forClassAndMethodAndLine("Test", "test", 1),
            evidence);
    final VulnerabilityBatch batch = new VulnerabilityBatch();
    batch.add(vulnerability);

    final String json = VulnerabilityEncoding.toJson(batch);

    final String expected =
        "{"
            + "  \"sources\": ["
            + "    { \"origin\": \"http.request.parameter\", \"name\": \"query\","
            + "      \"redacted\": true, \"pattern\": \"abcdefghijklmnopqrstu\" }"
            + "  ],"
            + "  \"vulnerabilities\": ["
            + "    { \"type\": \"SQL_INJECTION\", \"evidence\": { \"valueParts\": ["
            + "      { \"source\": 0, \"value\": \"select '\" },"
            + "      { \"source\": 0, \"redacted\": true, \"pattern\": \"ijk\" },"
            + "      { \"source\": 0, \"value\": \"' or '\" },"
            + "      { \"source\": 0, \"redacted\": true, \"pattern\": \"ijk\" },"
            + "      { \"source\": 0, \"value\": \"'\" }"
            + "    ] } }"
            + "  ]"
            + "}";

    JSONAssert.assertEquals(expected, json, JSONCompareMode.LENIENT);
  }
}
