package datadog.trace.plugin.csi.impl

import spock.lang.Specification

final class RegexpAdvicePointcutParserTest extends Specification {

  def 'resolve constructor'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("void datadog.trace.plugin.csi.samples.SignatureParserExample.<init>()")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == '<init>'
    signature.methodType.descriptor == '()V'
  }

  def 'resolve constructor with args'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("void datadog.trace.plugin.csi.samples.SignatureParserExample.<init>(java.lang.String)")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == '<init>'
    signature.methodType.descriptor == '(Ljava/lang/String;)V'
  }

  def 'resolve without args'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.noParams()")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'noParams'
    signature.methodType.descriptor == '()Ljava/lang/String;'
  }

  def 'resolve one param'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.oneParam(java.util.Map)")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'oneParam'
    signature.methodType.descriptor == '(Ljava/util/Map;)Ljava/lang/String;'
  }

  def 'resolve multiple params'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.multipleParams(java.lang.String, int, java.util.List)")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'multipleParams'
    signature.methodType.descriptor == '(Ljava/lang/String;ILjava/util/List;)Ljava/lang/String;'
  }

  def 'resolve varargs'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.varargs(java.lang.String[])")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'varargs'
    signature.methodType.descriptor == '([Ljava/lang/String;)Ljava/lang/String;'
  }

  def 'resolve primitive'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("int datadog.trace.plugin.csi.samples.SignatureParserExample.primitive()")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'primitive'
    signature.methodType.descriptor == '()I'
  }

  def 'resolve primitive array type'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("byte[] datadog.trace.plugin.csi.samples.SignatureParserExample.primitiveArray()")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'primitiveArray'
    signature.methodType.descriptor == '()[B'
  }

  def 'resolve object array type'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("java.lang.Object[] datadog.trace.plugin.csi.samples.SignatureParserExample.objectArray()")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'objectArray'
    signature.methodType.descriptor == '()[Ljava/lang/Object;'
  }

  def 'resolve multi dimensional object array type'() {
    setup:
    final pointcutParser = new RegexpAdvicePointcutParser()

    when:
    final signature = pointcutParser.parse("java.lang.Object[][][] datadog.trace.plugin.csi.samples.SignatureParserExample.objectArray()")

    then:
    signature.owner.className == 'datadog.trace.plugin.csi.samples.SignatureParserExample'
    signature.methodName == 'objectArray'
    signature.methodType.descriptor == '()[[[Ljava/lang/Object;'
  }
}
