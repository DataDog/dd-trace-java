package datadog.trace.api.datastreams

import datadog.context.Context
import spock.lang.Specification

class DataStreamsContextTest extends Specification {
  def 'test constructor'() {
    setup:
    def tags = DataStreamsTags.EMPTY

    when:
    def dsmContext = DataStreamsContext.fromTags(tags)

    then:
    dsmContext.tags() == tags
    dsmContext.defaultTimestamp() == 0
    dsmContext.payloadSizeBytes() == 0
    dsmContext.sendCheckpoint()

    when:
    dsmContext = DataStreamsContext.fromTagsWithoutCheckpoint(tags)

    then:
    dsmContext.tags() == tags
    dsmContext.defaultTimestamp() == 0
    dsmContext.payloadSizeBytes() == 0
    !dsmContext.sendCheckpoint()

    when:
    def timestamp = 123L
    def payloadSize = 456L
    dsmContext = DataStreamsContext.create(tags, timestamp, payloadSize)

    then:
    dsmContext.tags() == tags
    dsmContext.defaultTimestamp() == timestamp
    dsmContext.payloadSizeBytes() == payloadSize
    dsmContext.sendCheckpoint()
  }

  def 'test context store'() {
    setup:
    def tags = DataStreamsTags.EMPTY

    when:
    def dsmContext = DataStreamsContext.fromTags(tags)
    def context = dsmContext.storeInto(Context.root())

    then:
    DataStreamsContext.fromContext(context) == dsmContext
  }
}
