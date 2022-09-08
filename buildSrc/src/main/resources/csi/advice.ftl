<#if packageName??>package ${packageName};
<#assign customSpiPackage = spiPackageName?? && spiPackageName != packageName>
<#else>
<#assign customSpiPackage = !spiPackageName??>
</#if>
<#assign customSpiClass = (spiPackageName != 'datadog.trace.agent.tooling.csi' && spiClassName != 'CallSiteAdvice')>
<#assign hasHelpers = helperClassNames?size != 0>
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.Pointcut;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import com.google.auto.service.AutoService;
<#if customSpiPackage>import ${spiPackageName}.${spiClassName};</#if>

@AutoService(${spiClassName}.class)
public final class ${className} implements CallSiteAdvice<#if hasHelpers>, CallSiteAdvice.HasHelpers</#if>, Pointcut<#if customSpiClass>, ${spiClassName}</#if> {

  public Pointcut pointcut() {
    return this;
  }

  public void apply(final MethodVisitor visitor, final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
${applyBody}
  }

  public String type() {
    return "${type}";
  }

  public String method() {
    return "${method}";
  }

  public String descriptor() {
    return "${methodDescriptor}";
  }

<#if hasHelpers>
  public String[] helperClassNames() {
    return new String[] {
<#list helperClassNames as helper>      "${helper}"<#if helper?has_next>,</#if>
</#list>
    };
  }
</#if>
}
