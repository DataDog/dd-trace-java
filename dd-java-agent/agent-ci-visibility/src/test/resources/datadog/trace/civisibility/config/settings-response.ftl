{
  "data": {
    "type": "ci_app_tracers_test_service_settings",
    "id": "uuid",
    "attributes": {
      "itr_enabled": ${settings.itrEnabled?c},
      "code_coverage": ${settings.codeCoverageEnabled?c},
      "tests_skipping": ${settings.testsSkippingEnabled?c},
      "require_git": ${settings.gitUploadRequired?c},
      "flaky_test_retries_enabled": ${settings.flakyTestRetriesEnabled?c},
      "impacted_tests_enabled": ${settings.impactedTestsDetectionEnabled?c},
      "known_tests_enabled": ${settings.knownTestsEnabled?c},
      "coverage_report_upload_enabled": ${settings.coverageReportUploadEnabled?c},
      "di_enabled": ${settings.failedTestReplayEnabled?c},
      <#if settings.defaultBranch??>
        "default_branch": "${settings.defaultBranch}",
      </#if>
      "early_flake_detection": {
        "enabled": ${settings.earlyFlakeDetectionSettings.enabled?c},
        "slow_test_retries": {
          <#list settings.earlyFlakeDetectionSettings.executionsByDuration as execution>
            "${(execution.durationMillis > 60000)?then(execution.durationMillis / 60000 + 'm', execution.durationMillis / 1000 + 's')}": ${execution.executions}<#if execution?has_next>, </#if>
          </#list>
        },
        "faulty_session_threshold": ${settings.earlyFlakeDetectionSettings.faultySessionThreshold}
      },
      "test_management": {
        "enabled": ${settings.testManagementSettings.enabled?c},
        "attempt_to_fix_retries": ${settings.testManagementSettings.attemptToFixRetries}
      }
    }
  }
}
