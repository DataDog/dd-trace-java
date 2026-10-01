package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

public class TestSucceedWithReportEntry {

    @Test
    public void test_succeed_with_report_entry(TestReporter reporter) {
        reporter.publishEntry("key", "value");
    }
}
