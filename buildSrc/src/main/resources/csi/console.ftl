Call Site Instrumentation plugin results:
<#list results as result>
[<#if result.success>✓<#else>⨉</#if>] @CallSite ${result.specification.clazz.className}
<#list toList(result.errors) as error>
  [${error.errorCode}] ${error.message}
<#if error.cause??>${error.causeString}</#if>
</#list>
<#list toList(result.advices) as adviceResult>
  [<#if adviceResult.success>✓<#else>⨉</#if>] ${adviceResult.specification.advice.methodName} (${adviceResult.specification.signature})
  <#list toList(adviceResult.errors) as error>
    [${error.errorCode}] ${error.message}
<#if error.cause??>${error.causeString}</#if>
  </#list>
</#list>
</#list>
