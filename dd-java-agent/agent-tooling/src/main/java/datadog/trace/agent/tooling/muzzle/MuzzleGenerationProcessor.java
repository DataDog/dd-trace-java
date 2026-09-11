package datadog.trace.agent.tooling.muzzle;

import static java.util.Arrays.asList;
import static java.util.Collections.addAll;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceProcessor;
import datadog.trace.agent.tooling.advice.AdviceProcessorContext;
import datadog.trace.agent.tooling.advice.AdviceScanResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a neutral advice scan to references and emits the existing {@code $Muzzle} side class.
 */
public final class MuzzleGenerationProcessor implements AdviceProcessor<Reference[]> {
  @Override
  public Class<Reference[]> resultType() {
    return Reference[].class;
  }

  @Override
  public Reference[] process(AdviceScanResult scanResult, AdviceProcessorContext context) {
    InstrumenterModule module = context.getModule();

    Set<String> ignoredClasses = new HashSet<>(asList(module.muzzleIgnoredClassNames()));
    AdviceShader shader = AdviceShader.with(module.adviceShading());
    List<Reference> references = ReferenceCreator.createReferences(scanResult, shader);
    references.removeIf(reference -> ignoredClasses.contains(reference.className));
    Reference[] additionalReferences = module.additionalMuzzleReferences();
    if (additionalReferences != null) {
      addAll(references, additionalReferences);
    }

    MuzzleGenerator.generate(context.getTargetDirectory(), module, references);
    return references.toArray(new Reference[0]);
  }
}
