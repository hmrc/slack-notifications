/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.slacknotifications.controllers

import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.bootstrap.http.ErrorResponse
import uk.gov.hmrc.slacknotifications.model.NotificationRequest
import uk.gov.hmrc.slacknotifications.services.{AuthService, NotificationService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class NotificationController @Inject()(authService: AuthService, notificationService: NotificationService)(implicit ec: ExecutionContext)
  extends BaseController {

  def sendNotification(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    withAuthorization {
      withJsonBody[NotificationRequest] { notificationRequest =>
        val maybeService = hc.authorization.flatMap(AuthService.Service.fromAuthorization)
        (maybeService, authService.isValidatedNotificationRequest(notificationRequest)) match {
          case (Some(s), true) =>
            val sanitisedNotification = notificationService.sanitiseNotification(notificationRequest, s.displayName)
            notificationService.sendNotification(sanitisedNotification).map { results =>
              val asJson = Json.toJson(results)
              Logger.info(s"Request: $notificationRequest resulted in a notification result: $asJson")
              Ok(asJson)
            }
          case _ =>
            implicit val erFormats = Json.format[ErrorResponse]
            Future.successful(BadRequest(Json.toJson(ErrorResponse(400, "Invalid notification contents."))))
        }
      }
    }
  }

  def withAuthorization(block: => Future[Result])(implicit hc: HeaderCarrier): Future[Result] = {
    val maybeService = hc.authorization.flatMap(AuthService.Service.fromAuthorization)
    if (authService.isAuthorized(maybeService)) {
      block
    } else {
      val message = "Invalid credentials. Requires basic authentication"
      implicit val erFormats = Json.format[ErrorResponse]
      Future.successful(Unauthorized(Json.toJson(ErrorResponse(401, message))))
    }
  }

}
