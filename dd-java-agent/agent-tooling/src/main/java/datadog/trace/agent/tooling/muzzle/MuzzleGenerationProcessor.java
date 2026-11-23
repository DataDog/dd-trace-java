package datadog.trace.agent.tooling.muzzle;

import static java.util.Arrays.asList;
import static java.util.Collections.addAll;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceProcessor;
import datadog.trace.agent.tooling.advice.AdviceProcessorContext;
import datadog.trace.agent.tooling.advice.AdviceScanResult;
import datadog.trace.agent.tooling.bytebuddy.FieldAccessors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.AsmVisitorWrapper;

/**
 * Converts a neutral advice scan to references and emits the existing {@code $Muzzle} side class.
 */
public final class MuzzleGenerationProcessor implements AdviceProcessor {
  /**
   * The fields each {@code installFieldAccessors} call reads, as references on the types it
   * instruments, found by recording the module's type advice. Muzzle's bytecode scan can't see
   * them: the accessors are generated into the library class, not read by advice.
   */
  /**
   * The fields {@code accessors} reads, required on one of {@code types}: known matching types are
   * often alternatives (a class renamed across versions, classes from optional jars), and the
   * accessors are installed on each that has the fields, so muzzle passes if any one has them.
   */
  private static Reference eachOf(FieldAccessors accessors, String[] types) {
    Reference first = accessors.muzzleReference(types[0]);
    if (types.length == 1) {
      return first;
    }
    Reference[] others = new Reference[types.length - 1];
    for (int i = 1; i < types.length; i++) {
      others[i - 1] = accessors.muzzleReference(types[i]);
    }
    return new OrReference(first, others);
  }

  static List<Reference> fieldAccessorReferences(InstrumenterModule module) {
    List<Reference> references = new ArrayList<>();
    for (Instrumenter instrumenter : module.typeInstrumentations()) {
      if (!(instrumenter instanceof Instrumenter.HasTypeAdvice)) {
        continue;
      }
      String[] types;
      if (instrumenter instanceof Instrumenter.ForSingleType) {
        types = new String[] {((Instrumenter.ForSingleType) instrumenter).instrumentedType()};
      } else if (instrumenter instanceof Instrumenter.ForKnownTypes) {
        types = ((Instrumenter.ForKnownTypes) instrumenter).knownMatchingTypes();
      } else {
        // a type hierarchy has no type names to check against
        continue;
      }
      ((Instrumenter.HasTypeAdvice) instrumenter)
          .typeAdvice(
              new Instrumenter.TypeTransformer() {
                @Override
                public void applyAdvice(Instrumenter.TransformingAdvice typeAdvice) {}

                @Override
                public void applyAdvice(AsmVisitorWrapper typeVisitor) {
                  if (typeVisitor instanceof FieldAccessors) {
                    references.add(eachOf((FieldAccessors) typeVisitor, types));
                  }
                }
              });
    }
    return references;
  }

  @Override
  public void process(AdviceScanResult scanResult, AdviceProcessorContext context) {
    InstrumenterModule module = context.getModule();

    Set<String> ignoredClasses = new HashSet<>(asList(module.muzzleIgnoredClassNames()));
    addAll(ignoredClasses, context.getHelperClassNames());
    AdviceShader shader = AdviceShader.with(module.adviceShading());
    List<Reference> references = ReferenceCreator.createReferences(scanResult, shader);
    references.removeIf(reference -> ignoredClasses.contains(reference.className));
    Reference[] additionalReferences = module.additionalMuzzleReferences();
    if (additionalReferences != null) {
      addAll(references, additionalReferences);
    }
    references.addAll(fieldAccessorReferences(module));

    MuzzleGenerator.generate(context.getTargetDirectory(), module, references);
  }
}
