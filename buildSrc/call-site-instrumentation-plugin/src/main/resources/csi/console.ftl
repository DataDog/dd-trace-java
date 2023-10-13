<#-- @ftlvariable name="results" type="java.util.List<datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult>" -->
Call Site Instrumentation plugin results:
<#list results as result>
[<#if result.success>✓<#else>⨉</#if>] @CallSite ${result.specification.clazz.className}
<#list result.errors as error>
  [${error.errorCode}] ${error.message}
<#if error.cause??>${error.causeString}</#if>
</#list>
</#list>
