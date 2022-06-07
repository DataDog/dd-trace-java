package datadog.trace.agent.tooling.muzzle;

import net.bytebuddy.pool.TypePool;

/** Provides additional muzzle references at runtime based on available types. */
public interface ReferenceProvider {
  Iterable<Reference> buildReferences(TypePool typePool);
}
