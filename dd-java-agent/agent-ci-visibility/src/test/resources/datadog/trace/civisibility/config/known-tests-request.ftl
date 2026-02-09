{
  "data": {
    "type"      : "ci_app_libraries_tests_request",
    "id"        : "${uid}",
    "attributes": {
      "repository_url": "${tracerEnvironment.repositoryUrl}",
      "service"       : "${tracerEnvironment.service}",
      "env"           : "${tracerEnvironment.env}",
      "page_info": {<#if pageInfo.pageState??>
        "page_state": "${pageInfo.pageState}"</#if>
      }
    }
  }
}
