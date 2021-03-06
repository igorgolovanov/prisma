package com.prisma.api.mutactions

import com.prisma.api.ApiDependencies
import com.prisma.api.connector.{PublishSubscriptionEvent, ServerSideSubscription, SideEffectMutaction}
import com.prisma.messagebus.PubSubPublisher
import com.prisma.messagebus.pubsub.Only
import com.prisma.shared.models.WebhookDelivery
import com.prisma.subscriptions.{SubscriptionExecutor, Webhook}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

trait SideEffectMutactionExecutor {
  def execute(mutactions: Vector[SideEffectMutaction]): Future[Unit]
}

case class SideEffectMutactionExecutorImpl()(implicit apiDependencies: ApiDependencies, ec: ExecutionContext) extends SideEffectMutactionExecutor {

  override def execute(mutactions: Vector[SideEffectMutaction]): Future[Unit] = {
    Future.sequence(mutactions.map(execute)).map(_ => ())
  }

  def execute(mutaction: SideEffectMutaction): Future[Unit] = mutaction match {
    case mutaction: PublishSubscriptionEvent =>
      PublishSubscriptionEventExecutor.execute(mutaction, apiDependencies.sssEventsPubSub)
    case mutaction: ServerSideSubscription =>
      ServerSideSubscriptionExecutor.execute(mutaction)
  }

}

object PublishSubscriptionEventExecutor {
  implicit val anyFormat = com.prisma.util.json.JsonFormats.MapJsonWriter

  def execute(mutaction: PublishSubscriptionEvent, subscriptionEventsPublisher: PubSubPublisher[String]): Future[Unit] = {
    val PublishSubscriptionEvent(project, value, mutationName) = mutaction
    val topic                                                  = Only(s"subscription:event:${project.id}:$mutationName")
    println(s"PUBLISHING SUBSCRIPTION EVENT TO $topic")
    subscriptionEventsPublisher.publish(topic, value.toJson.compactPrint)
    Future.unit
  }
}

object ServerSideSubscriptionExecutor {
  def execute(mutaction: ServerSideSubscription)(implicit apiDependencies: ApiDependencies): Future[Unit] = {
    mutaction.function.delivery match {
      case webhookDelivery: WebhookDelivery =>
        deliverWebhook(mutaction, webhookDelivery)
      case _ =>
        Future.unit
    }
  }

  def deliverWebhook(mutaction: ServerSideSubscription, webhookDelivery: WebhookDelivery)(implicit apiDependencies: ApiDependencies): Future[Unit] = {
    import apiDependencies.executionContext
    val ServerSideSubscription(project, model, mutationType, function, nodeId, requestId, updatedFields, previousValues) = mutaction
    val subscriptionResult = SubscriptionExecutor.execute(
      project = project,
      model = model,
      mutationType = mutationType,
      previousValues = previousValues,
      updatedFields = updatedFields,
      query = function.query,
      variables = JsObject.empty,
      nodeId = nodeId,
      requestId = s"subscription:server_side:${project.id}",
      operationName = None,
      skipPermissionCheck = true,
      alwaysQueryMasterDatabase = true
    )
    subscriptionResult.map {
      case Some(JsObject(fields)) if fields.contains("data") =>
        val webhook = Webhook(
          projectId = project.id,
          functionName = function.name,
          requestId = requestId,
          url = webhookDelivery.url,
          payload = JsObject(fields).compactPrint,
          id = requestId,
          headers = webhookDelivery.headers.toMap
        )
        apiDependencies.webhookPublisher.publish(webhook)
      case _ =>
        ()
    }
  }
}
