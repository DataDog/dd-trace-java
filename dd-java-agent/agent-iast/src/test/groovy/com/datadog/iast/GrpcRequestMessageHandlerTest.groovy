package com.datadog.iast

import com.datadog.iast.protobuf.Test2
import com.datadog.iast.protobuf.Test3
import com.datadog.iast.util.ObjectVisitor
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.test.util.DDSpecification
import foo.bar.VisitableClass

import java.util.function.Predicate

import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE

class GrpcRequestMessageHandlerTest extends DDSpecification {

  private PropagationModule propagation
  private IastRequestContext iastCtx
  private RequestContext ctx

  void setup() {
    propagation = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagation)
    iastCtx = Mock(IastRequestContext)
    ctx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> iastCtx
    }
  }

  void 'the handler does nothing without propagation'() {
    given:
    final handler = new GrpcRequestMessageHandler()
    InstrumentationBridge.clearIastModules()

    when:
    handler.apply(ctx, [:])

    then:
    0 * _
  }

  void 'the handler does nothing with null values'() {
    given:
    final handler = new GrpcRequestMessageHandler()

    when:
    handler.apply(ctx, null)

    then:
    0 * _
  }

  void 'the handler forwards objects to the propagation module'() {
    given:
    final target = [:]
    final handler = new GrpcRequestMessageHandler()

    when:
    handler.apply(ctx, target)

    then:
    1 * propagation.taintDeeply(iastCtx, SourceTypes.GRPC_BODY, target, _ as Predicate<Class<?>>)
  }

  void 'the handler only takes into account protobuf v.#protobufVersion related messages'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor) {
      visit(_ as String, _ as Object) >> {
        println 'feo'
        return CONTINUE
      }
    }
    final url = 'https://dd.datad0g.com/'
    final nonProtobufMessage = new VisitableClass(name: 'test')
    final filter = GrpcRequestMessageHandler::isProtobufArtifact

    when: 'the message is not a protobuf instance'
    ObjectVisitor.visit(nonProtobufMessage, visitor, filter)

    then: 'only the root object is visited'
    1 * visitor.visit('root', nonProtobufMessage) >> CONTINUE
    0 * visitor._

    when: 'the message is a protobuf message'
    ObjectVisitor.visit(protobufMessage, visitor, filter)

    then: 'all the properties are visited'
    1 * visitor.visit('root', protobufMessage) >> CONTINUE
    1 * visitor.visit('root.child_.optional_', 'optional') >> CONTINUE
    1 * visitor.visit('root.child_.required_', 'required') >> CONTINUE
    1 * visitor.visit('root.child_.repeated_[0]', 'repeated0') >> CONTINUE
    1 * visitor.visit('root.child_.repeated_[1]', 'repeated1') >> CONTINUE
    // for maps we go inside com.google.protobuf.MapField and extract properties
    1 * visitor.visit('root.child_.map_.mapData[]', 'key') >> CONTINUE
    1 * visitor.visit('root.child_.map_.mapData[key]', 'value') >> CONTINUE

    where:
    protobufVersion << ['2', '3']
    protobufMessage << [buildProto2Message(), buildProto3Message()]
  }

  private static def buildProto2Message() {
    final child = Test2.Proto2Child.newBuilder()
    .setOptional("optional")
    .setRequired("required")
    .addAllRepeated(Arrays.asList('repeated0', 'repeated1'))
    .putMap('key', 'value')
    .build()
    return Test2.Proto2Parent.newBuilder().setChild(child).build()
  }

  private static def buildProto3Message() {
    final child = Test3.Proto3Child.newBuilder()
    .setOptional("optional")
    .setRequired("required")
    .addAllRepeated(Arrays.asList('repeated0', 'repeated1'))
    .putMap('key', 'value')
    .build()
    return Test3.Proto3Parent.newBuilder().setChild(child).build()
  }
}
