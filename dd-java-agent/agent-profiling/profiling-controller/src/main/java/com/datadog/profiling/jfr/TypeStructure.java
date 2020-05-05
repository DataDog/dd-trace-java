package com.datadog.profiling.jfr;

import java.util.List;

final class TypeStructure {
  final List<TypedField> fields;
  final List<JFRAnnotation> annotations;

  TypeStructure(List<TypedField> fields, List<JFRAnnotation> annotations) {
    this.fields = fields;
    this.annotations = annotations;
  }
}
