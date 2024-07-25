package com.datadog.appsec.gateway;

import com.datadog.appsec.event.EventProducerService;

public class SubscribersCache {

  private volatile EventProducerService.DataSubscriberInfo initialReqDataSubInfo;
  private volatile EventProducerService.DataSubscriberInfo rawRequestBodySubInfo;
  private volatile EventProducerService.DataSubscriberInfo requestBodySubInfo;
  private volatile EventProducerService.DataSubscriberInfo pathParamsSubInfo;
  private volatile EventProducerService.DataSubscriberInfo respDataSubInfo;
  private volatile EventProducerService.DataSubscriberInfo grpcServerMethodSubInfo;
  private volatile EventProducerService.DataSubscriberInfo grpcServerRequestMsgSubInfo;
  private volatile EventProducerService.DataSubscriberInfo graphqlServerRequestMsgSubInfo;
  private volatile EventProducerService.DataSubscriberInfo requestEndSubInfo;
  private volatile EventProducerService.DataSubscriberInfo dbSqlQuerySubInfo;

  public EventProducerService.DataSubscriberInfo getInitialReqDataSubInfo() {
    return initialReqDataSubInfo;
  }

  public void setInitialReqDataSubInfo(
      EventProducerService.DataSubscriberInfo initialReqDataSubInfo) {
    this.initialReqDataSubInfo = initialReqDataSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getRawRequestBodySubInfo() {
    return rawRequestBodySubInfo;
  }

  public void setRawRequestBodySubInfo(
      EventProducerService.DataSubscriberInfo rawRequestBodySubInfo) {
    this.rawRequestBodySubInfo = rawRequestBodySubInfo;
  }

  public EventProducerService.DataSubscriberInfo getRequestBodySubInfo() {
    return requestBodySubInfo;
  }

  public void setRequestBodySubInfo(EventProducerService.DataSubscriberInfo requestBodySubInfo) {
    this.requestBodySubInfo = requestBodySubInfo;
  }

  public EventProducerService.DataSubscriberInfo getPathParamsSubInfo() {
    return pathParamsSubInfo;
  }

  public void setPathParamsSubInfo(EventProducerService.DataSubscriberInfo pathParamsSubInfo) {
    this.pathParamsSubInfo = pathParamsSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getRespDataSubInfo() {
    return respDataSubInfo;
  }

  public void setRespDataSubInfo(EventProducerService.DataSubscriberInfo respDataSubInfo) {
    this.respDataSubInfo = respDataSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getGrpcServerMethodSubInfo() {
    return grpcServerMethodSubInfo;
  }

  public void setGrpcServerMethodSubInfo(
      EventProducerService.DataSubscriberInfo grpcServerMethodSubInfo) {
    this.grpcServerMethodSubInfo = grpcServerMethodSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getGrpcServerRequestMsgSubInfo() {
    return grpcServerRequestMsgSubInfo;
  }

  public void setGrpcServerRequestMsgSubInfo(
      EventProducerService.DataSubscriberInfo grpcServerRequestMsgSubInfo) {
    this.grpcServerRequestMsgSubInfo = grpcServerRequestMsgSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getGraphqlServerRequestMsgSubInfo() {
    return graphqlServerRequestMsgSubInfo;
  }

  public void setGraphqlServerRequestMsgSubInfo(
      EventProducerService.DataSubscriberInfo graphqlServerRequestMsgSubInfo) {
    this.graphqlServerRequestMsgSubInfo = graphqlServerRequestMsgSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getRequestEndSubInfo() {
    return requestEndSubInfo;
  }

  public void setRequestEndSubInfo(EventProducerService.DataSubscriberInfo requestEndSubInfo) {
    this.requestEndSubInfo = requestEndSubInfo;
  }

  public EventProducerService.DataSubscriberInfo getDbSqlQuerySubInfo() {
    return dbSqlQuerySubInfo;
  }

  public void setDbSqlQuerySubInfo(EventProducerService.DataSubscriberInfo dbSqlQuerySubInfo) {
    this.dbSqlQuerySubInfo = dbSqlQuerySubInfo;
  }
}
