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

package uk.gov.hmrc.slacknotifications.controllers

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{Format, JsValue, Json, OFormat}
import play.api.mvc.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.bootstrap.backend.http.ErrorResponse
import uk.gov.hmrc.slacknotifications.model.{NotificationRequest, NotificationResult}
import uk.gov.hmrc.slacknotifications.services.{AuthService, LegacyNotificationService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton()
class LegacyNotificationController @Inject()(
  authService         : AuthService,
  notificationService : LegacyNotificationService,
  controllerComponents: ControllerComponents
)(using ExecutionContext
) extends BackendController(controllerComponents) with Logging:

  def sendNotification(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    withAuthorization { authenticatedService =>
      withJsonBody[NotificationRequest] { notificationRequest =>
        notificationService.sendNotification(notificationRequest, authenticatedService).map { results =>
          given Format[NotificationResult] = NotificationResult.format
          val asJson = Json.toJson(results)
          logger.info(s"Request: $notificationRequest resulted in a notification result: $asJson")
          Ok(asJson)
        }
      }
    }
  }

  def withAuthorization(fn: AuthService.ClientService => Future[Result])(using hc: HeaderCarrier): Future[Result] =
    def unauthorized: Future[Result] =
      val message                  = "Invalid credentials. Requires basic authentication"
      given OFormat[ErrorResponse] = Json.format[ErrorResponse]
      Future.successful(Unauthorized(Json.toJson(ErrorResponse(401, message, None, None))))

    hc.authorization.flatMap(AuthService.ClientService.fromAuthorization).fold(unauthorized): service =>
      if authService.isAuthorized(service) then
        fn(service)
      else unauthorized
