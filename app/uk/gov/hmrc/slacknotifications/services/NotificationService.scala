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

package uk.gov.hmrc.slacknotifications.services

import javax.inject.Inject
import play.api.Logger
import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.slacknotifications.connectors.{SlackConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.GithubRepository
import uk.gov.hmrc.slacknotifications.model.{NotificationRequest, SlackMessage}

class NotificationService @Inject()(
  slackConnector: SlackConnector,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector) {
  import NotificationService._

  def sendNotification(notificationRequest: NotificationRequest)(
    implicit hc: HeaderCarrier): Future[Either[Error, Unit]] =
    notificationRequest.channelLookup match {
      case GithubRepository(_, name) =>
        teamsAndRepositoriesConnector.getRepositoryDetails(name).map {
          case Some(_) => Right(())
          case None    => Left(RepositoryNotFound(name))
        }
    }

  private[services] def sendSlackMessage(slackMessage: SlackMessage)(
    implicit hc: HeaderCarrier): Future[Either[OtherError, Unit]] =
    slackConnector.sendMessage(slackMessage).map { response =>
      response.status match {
        case 200 => Right(())
        case _ =>
          Logger.warn(s"Slack API returned error, status=${response.status} and body='${response.body}'")
          Left(OtherError(response.status, response.body))
      }
    }
}

object NotificationService {
  sealed trait Error
  final case class OtherError(statusCode: Int, message: String) extends Error
  final case class RepositoryNotFound(repoName: String) extends Error
}
