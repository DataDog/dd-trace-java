package datadog.trace.agent.tooling.iast.stratum.parser;

import datadog.trace.agent.tooling.iast.stratum.SourceMapException;

abstract class Builder {

  private final String section;

  Builder(final String section) {
    this.section = section;
  }

  String getSectionName() {
    return section;
  }

  abstract void build(State paramState, String[] paramArrayOfString) throws SourceMapException;
}
