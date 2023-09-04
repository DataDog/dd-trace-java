package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification


class ResponseStartedCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'set status and publish'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    // TODO: we probably should not mock this.
    MaybePublishRequestDataCallback maybePublishCallback = Mock()
    final cb = new ResponseStartedCallback(maybePublishCallback)

    when:
    cb.apply(ctx, 205)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * appSecCtx.isRespDataPublished() >> false
    1 * appSecCtx.setResponseStatus(205)
    1 * maybePublishCallback.apply(appSecCtx)
    0 * _
  }

  void 'does nothing if there is no appsec context'() {
    given:
    RequestContext ctx = Mock()
    MaybePublishRequestDataCallback maybePublishCallback = Mock()
    final cb = new ResponseStartedCallback(maybePublishCallback)

    when:
    cb.apply(ctx, 205)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> null
    0 * _
  }

  void 'does nothing if response data was already published'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    MaybePublishRequestDataCallback maybePublishCallback = Mock()
    final cb = new ResponseStartedCallback(maybePublishCallback)

    when:
    cb.apply(ctx, 205)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * appSecCtx.isRespDataPublished() >> true
    0 * _
  }
}
