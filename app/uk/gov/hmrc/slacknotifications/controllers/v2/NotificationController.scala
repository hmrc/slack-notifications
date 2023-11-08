/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.slacknotifications.controllers.v2

import play.api.Logging
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.internalauth.client.{AuthenticatedRequest, BackendAuthComponents, IAAction, Predicate, Resource}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.slacknotifications.controllers.v2.NotificationController.SendNotificationRequest
import uk.gov.hmrc.slacknotifications.model.{ChannelLookup, NotificationResult, SendNotificationResponse}
import uk.gov.hmrc.slacknotifications.services.NotificationService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class NotificationController @Inject()(
  auth                : BackendAuthComponents
, controllerComponents: ControllerComponents
, notificationService : NotificationService
)(implicit
  ec: ExecutionContext
) extends BackendController(controllerComponents) with Logging {

  private val predicate: Predicate.Permission =
    Predicate.Permission(Resource.from("slack-notifications", "v2/notification"), IAAction("SEND_NOTIFICATION"))

  def sendNotification(): Action[JsValue] =
    auth.authorizedAction(predicate).async(parse.json) { implicit request: AuthenticatedRequest[JsValue, _] =>
      implicit val snrR: Reads[SendNotificationRequest] = SendNotificationRequest.reads
      withJsonBody[SendNotificationRequest] { snr =>
        notificationService.sendNotification(snr).value.map {
          case Left(nr) =>
            implicit val format: Format[NotificationResult] = NotificationResult.format
            val asJson = Json.toJson(nr)
            logger.info(s"Request: $snr resulted in a notification result: $asJson")
            Ok(asJson)
          case Right(response) =>
            implicit val writes: Writes[SendNotificationResponse] = SendNotificationResponse.writes
            val asJson = Json.toJson(response)
            logger.info(s"Request: $snr was queued with msgId: ${response.msgId.toString}")
            Ok(asJson)
        }
      }
    }

}

object NotificationController {
  final case class SendNotificationRequest(
    displayName  : String,
    emoji        : String,
    channelLookup: ChannelLookup,
    text         : String,
    blocks       : Seq[JsObject],
    attachments  : Seq[JsObject]
  )

  object SendNotificationRequest {
    implicit val clR: Reads[ChannelLookup] = ChannelLookup.reads
    val reads: Reads[SendNotificationRequest] =
      ( (__ \ "displayName"  ).read[String]
      ~ (__ \ "emoji"        ).read[String]
      ~ (__ \ "channelLookup").read[ChannelLookup]
      ~ (__ \ "text"         ).read[String]
      ~ (__ \ "blocks"       ).readWithDefault[Seq[JsObject]](Seq.empty)
      ~ (__ \ "attachments"  ).readWithDefault[Seq[JsObject]](Seq.empty)
      )(SendNotificationRequest.apply _)
  }
}
