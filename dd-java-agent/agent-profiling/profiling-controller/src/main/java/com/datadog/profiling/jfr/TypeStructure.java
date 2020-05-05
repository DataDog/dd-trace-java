package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.List;

final class TypeStructure {
  static final TypeStructure EMPTY =
      new TypeStructure(Collections.emptyList(), Collections.emptyList());

  final List<TypedField> fields;
  final List<JFRAnnotation> annotations;

  TypeStructure(List<TypedField> fields, List<JFRAnnotation> annotations) {
    this.fields = fields;
    this.annotations = annotations;
  }
}
