{
  "data": {
    "type"      : "ci_app_libraries_tests_request",
    "id"        : "${uid}",
    "attributes": {
      "service"       : "${tracerEnvironment.service}",
      "env"           : "${tracerEnvironment.env}",
      "repository_url": "${tracerEnvironment.repositoryUrl}",
      "branch"        : "${tracerEnvironment.branch}",
      "sha"           : "${tracerEnvironment.sha}",
      "test_level"    : "${tracerEnvironment.testLevel}",
      "configurations": {
        "os.platform"         : "${tracerEnvironment.configurations.osPlatform}",
        "os.architecture"     : "${tracerEnvironment.configurations.osArchitecture}",
        "os.arch"             : "${tracerEnvironment.configurations.osArchitecture}",
        "os.version"          : "${tracerEnvironment.configurations.osVersion}",
        "runtime.name"        : "${tracerEnvironment.configurations.runtimeName}",
        "runtime.version"     : "${tracerEnvironment.configurations.runtimeVersion}",
        "runtime.vendor"      : "${tracerEnvironment.configurations.runtimeVendor}",
        "runtime.architecture": "${tracerEnvironment.configurations.runtimeArchitecture}",
        "custom"              : {
          <#list tracerEnvironment.configurations.custom as customTag, customValue>
            "${customTag}": "${customValue}"<#if customTag?has_next>, </#if>
          </#list>
        }
      },
      "page_info": {<#if pageInfo.pageState??>
        "page_state": "${pageInfo.pageState}"</#if>
      }
    }
  }
}
