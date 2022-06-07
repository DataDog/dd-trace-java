package datadog.trace.agent.tooling.muzzle;

/** A reference that checks an alternative spec if the first doesn't match. */
public class OrReference extends Reference {
  public final Reference or;

  public OrReference(Reference either, Reference or) {
    super(
        either.sources,
        either.flags,
        either.className,
        either.superName,
        either.interfaces,
        either.fields,
        either.methods);
    this.or = or;
  }
}
