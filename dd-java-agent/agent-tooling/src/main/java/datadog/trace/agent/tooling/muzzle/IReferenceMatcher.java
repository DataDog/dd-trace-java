package datadog.trace.agent.tooling.muzzle;

import java.util.ArrayList;
import java.util.List;

public interface IReferenceMatcher {
  boolean matches(ClassLoader loader);

  List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader);

  class ConjunctionReferenceMatcher implements IReferenceMatcher {
    private final IReferenceMatcher m1;
    private final IReferenceMatcher m2;

    public ConjunctionReferenceMatcher(IReferenceMatcher m1, IReferenceMatcher m2) {
      this.m1 = m1;
      this.m2 = m2;
    }

    @Override
    public boolean matches(ClassLoader loader) {
      return m1.matches(loader) && m2.matches(loader);
    }

    @Override
    public List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader) {
      List<Reference.Mismatch> mm1 = m1.getMismatchedReferenceSources(loader);
      List<Reference.Mismatch> mm2 = m2.getMismatchedReferenceSources(loader);
      if (mm2.isEmpty()) {
        return mm1;
      }
      if (mm1.isEmpty()) {
        return mm2;
      }
      List<Reference.Mismatch> allMm = new ArrayList<>();
      allMm.addAll(mm1);
      allMm.addAll(mm2);
      return allMm;
    }
  }
}
