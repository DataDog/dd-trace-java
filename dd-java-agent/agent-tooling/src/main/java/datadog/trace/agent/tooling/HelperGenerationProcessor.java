package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.HelperScanner.isHelperClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.advice.AdviceScanResult;
import datadog.trace.agent.tooling.advice.AdviceScanResult.ClassInfo;
import datadog.trace.agent.tooling.advice.AdviceScanResult.Usage;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.collection.ArrayFactory;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;

/** Resolves injectable helpers and generates {@code helperClassNames()} when none are declared. */
public final class HelperGenerationProcessor {
  private static final String MUZZLE_REFERENCE_PREFIX = Reference.class.getName();

  public String[] resolveHelpers(AdviceScanResult scanResult, InstrumenterModule module) {
    String[] declaredHelpers = module.helperClassNames();
    if (declaredHelpers.length > 0) {
      return declaredHelpers;
    }

    Set<String> adviceRoots = new HashSet<>(scanResult.getAdviceRoots());
    Set<String> helpers = new LinkedHashSet<>();
    for (ClassInfo info : scanResult.getClasses().values()) {
      if (info.isScanned()
          && !adviceRoots.contains(info.getClassName())
          && isHelperClass(info.getClassName(), info.isFromModuleOutput())
          && !isBuildTimeOnly(info)) {
        helpers.add(info.getClassName());
      }
    }

    List<String> ordered = new ArrayList<>(helpers.size());
    Set<String> visited = new HashSet<>();
    for (String helper : helpers) {
      addInDependencyOrder(helper, helpers, scanResult, visited, ordered);
    }
    return ordered.toArray(new String[0]);
  }

  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder, String[] helpers, InstrumenterModule module) {
    if (helpers.length == 0 || module.helperClassNames().length > 0) {
      return builder;
    }
    List<StackManipulation> values = new ArrayList<>(helpers.length);
    for (String helper : helpers) {
      values.add(new TextConstant(helper));
    }
    return builder
        .method(named("helperClassNames").and(takesArguments(0)).and(returns(String[].class)))
        .intercept(
            new Implementation.Simple(
                ArrayFactory.forType(
                        new TypeDescription.ForLoadedType(String.class).asGenericType())
                    .withValues(values),
                MethodReturn.REFERENCE));
  }

  private static boolean isBuildTimeOnly(ClassInfo info) {
    for (String dependency : info.getRequiredDependencies()) {
      if (dependency.startsWith(MUZZLE_REFERENCE_PREFIX)) {
        return true;
      }
    }
    // Some muzzle helpers only construct references inside methods, without implementing a
    // provider.
    for (Usage usage : info.getUsages()) {
      String owner = usage.getOwner();
      if (owner != null && owner.startsWith(MUZZLE_REFERENCE_PREFIX)) {
        return true;
      }
    }
    return false;
  }

  private static void addInDependencyOrder(
      String className,
      Set<String> helpers,
      AdviceScanResult scanResult,
      Set<String> visited,
      List<String> ordered) {
    if (!visited.add(className)) {
      return;
    }
    ClassInfo info = scanResult.getClassInfo(className);
    for (String dependency : info.getRequiredDependencies()) {
      if (helpers.contains(dependency)) {
        addInDependencyOrder(dependency, helpers, scanResult, visited, ordered);
      }
    }
    ordered.add(className);
  }
}
