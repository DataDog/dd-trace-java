package datadog.trace.agent.tooling.bytebuddy.outline;

import net.bytebuddy.description.type.TypeDescription;

/** Provide as much detail as we can from just a type-name. */
abstract class WithName extends TypeDescription.AbstractBase.OfSimpleType.WithDelegation {
  final String name;

  WithName(String name) {
    this.name = name;
  }

  @Override
  public final String getName() {
    return name;
  }

  @Override
  public final String getActualName() {
    return name;
  }

  @Override
  public final String getTypeName() {
    return name;
  }

  @Override
  public final String getSimpleName() {
    int afterLastDot = name.lastIndexOf('.') + 1;
    if (name.indexOf('$', afterLastDot) < 0) {
      return name.substring(afterLastDot);
    }
    return super.getSimpleName(); // names with $ require the enclosing type to verify
  }
}
