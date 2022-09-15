<#if packageName??>package ${packageName};
<#assign customSpiPackage = spiPackageName?? && spiPackageName != packageName>
<#else>
<#assign customSpiPackage = !spiPackageName??>
</#if>
<#assign customSpiClass = (spiPackageName != 'datadog.trace.agent.tooling.csi' && spiClassName != 'CallSiteAdvice')>
<#assign hasHelpers = helperClassNames?size != 0>
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.Pointcut;
import datadog.trace.agent.tooling.csi.<#if dynamicInvoke>InvokeDynamicAdvice<#else>InvokeAdvice</#if>;
import net.bytebuddy.jar.asm.Opcodes;
<#if dynamicInvoke>import net.bytebuddy.jar.asm.Handle;</#if>
import com.google.auto.service.AutoService;
<#if customSpiPackage>import ${spiPackageName}.${spiClassName};</#if>

@AutoService(${spiClassName}.class)
public final class ${className} implements CallSiteAdvice, Pointcut, <#if dynamicInvoke>InvokeDynamicAdvice<#else>InvokeAdvice</#if><#if computeMaxStack>, CallSiteAdvice.HasFlags</#if><#if hasHelpers>, CallSiteAdvice.HasHelpers</#if><#if customSpiClass>, ${spiClassName}</#if> {

  @Override
  public Pointcut pointcut() {
    return this;
  }

<#if dynamicInvoke>
  public void apply(final MethodHandler handler, final String name, final String descriptor, final Handle bootstrapMethodHandle, final Object... bootstrapMethodArguments) {
<#else>
  public void apply(final MethodHandler handler, final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
</#if>
${applyBody}
  }

  @Override
  public String type() {
    return "${type}";
  }

  @Override
  public String method() {
    return "${method}";
  }

  @Override
  public String descriptor() {
    return "${methodDescriptor}";
  }

<#if computeMaxStack>
  @Override
  public int flags() {
    return COMPUTE_MAX_STACK;
  }
</#if>

<#if hasHelpers>
  @Override
  public String[] helperClassNames() {
    return new String[] {
<#list helperClassNames as helper>      "${helper}"<#if helper?has_next>,</#if>
</#list>
    };
  }
</#if>
}
