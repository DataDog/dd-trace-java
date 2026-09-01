package datadog.trace.agent.tooling.muzzle;

import java.io.File;
import java.util.List;

/** Typed muzzle processor output available to later advice processors. */
public final class MuzzleGenerationResult {
  private final Reference[] references;
  private final File generatedClass;

  MuzzleGenerationResult(List<Reference> references, File generatedClass) {
    this.references = references.toArray(new Reference[0]);
    this.generatedClass = generatedClass;
  }

  public Reference[] getReferences() {
    return references.clone();
  }

  public File getGeneratedClass() {
    return generatedClass;
  }
}
