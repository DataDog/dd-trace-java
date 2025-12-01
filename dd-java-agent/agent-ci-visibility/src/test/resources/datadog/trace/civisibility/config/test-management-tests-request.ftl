{
  "data": {
    "type"      : "ci_app_libraries_tests_request",
    "id"        : "${uid}",
    "attributes": {
      "repository_url"  : "${tracerEnvironment.repositoryUrl}",
      "commit_message"  : "${tracerEnvironment.commitMessage}",
      "module"          : "${tracerEnvironment.configurations.testBundle}",
      "sha"             : "${tracerEnvironment.sha}",
      "branch"          : "${tracerEnvironment.branch}"
      }
    }
  }
}
