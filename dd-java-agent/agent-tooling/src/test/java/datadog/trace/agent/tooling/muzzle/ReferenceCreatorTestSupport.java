package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanResult;
import datadog.trace.agent.tooling.advice.AdviceScanner;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReferenceCreatorTestSupport {
  private ReferenceCreatorTestSupport() {}

  public static Map<String, Reference> referencesFrom(Class<?> adviceClass) {
    AdviceScanResult scanResult = AdviceScanner.scan(new AdviceModule(adviceClass.getName()));
    return byName(ReferenceCreator.createReferences(scanResult, null));
  }

  static Map<String, Reference> byName(Collection<Reference> references) {
    Map<String, Reference> referencesByName = new LinkedHashMap<>();
    for (Reference reference : references) {
      referencesByName.put(reference.className, reference);
    }
    return referencesByName;
  }

  private static final class AdviceModule extends InstrumenterModule
      implements Instrumenter.HasMethodAdvice {
    private final String adviceClass;

    private AdviceModule(String adviceClass) {
      super("jdbc");
      this.adviceClass = adviceClass;
    }

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(null, adviceClass);
    }
  }
}
