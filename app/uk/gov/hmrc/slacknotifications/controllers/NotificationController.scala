/*
 * Copyright 2018 HM Revenue & Customs
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

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BaseController
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.slacknotifications.model.NotificationRequest
import uk.gov.hmrc.slacknotifications.services.NotificationService
import uk.gov.hmrc.slacknotifications.services.NotificationService.RepositoryNotFound

@Singleton()
class NotificationController @Inject()(notificationService: NotificationService) extends BaseController {

  def sendNotification() = Action.async(parse.json) { implicit request =>
    withJsonBody[NotificationRequest] { notificationRequest =>
      notificationService.sendNotification(notificationRequest).map {
        case Valid(_)        => Ok(Json.obj("message" -> "Slack messages sent successfully"))
        case Invalid(errors) => handleErrors(errors)
      }
    }
  }

  def handleErrors(errors: NonEmptyList[NotificationService.Error]): Result =
    errors
      .find(_.isInstanceOf[RepositoryNotFound])
      .map { repoNotFoundError =>
        BadRequest(Json.toJson(Errors.one(repoNotFoundError.message)))
      }
      .getOrElse {
        InternalServerError(Json.toJson(Errors(errors.map(_.message))))
      }

}
