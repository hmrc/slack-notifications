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

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import javax.inject.Inject
import play.api.Logger
import play.api.libs.json.{Format, JsValue, Json}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.GithubRepository
import uk.gov.hmrc.slacknotifications.model.{NotificationRequest, SlackMessage}

class NotificationService @Inject()(
  slackConnector: SlackConnector,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  userManagementConnector: UserManagementConnector) {
  import NotificationService._

  def sendNotification(notificationRequest: NotificationRequest)(
    implicit hc: HeaderCarrier): Future[ValidatedNel[Error, Unit]] =
    notificationRequest.channelLookup match {
      case GithubRepository(_, name) =>
        withExistingRepository(name) { repositoryDetails =>
          withExistingTeams(name, repositoryDetails) { teamNames =>
            traverseFuturesSequentially(teamNames) { teamName =>
              withExistingSlackChannel(teamName) { slackChannel =>
                sendSlackMessage(fromNotification(notificationRequest, slackChannel)).map(_.toValidatedNel)
              }
            }.map(flatten)
          }
        }
    }

  private def fromNotification(notificationRequest: NotificationRequest, slackChannel: String): SlackMessage =
    SlackMessage(
      channel     = slackChannel,
      text        = notificationRequest.text,
      username    = notificationRequest.username,
      icon_emoji  = notificationRequest.iconEmoji,
      attachments = notificationRequest.attachments
    )

  private def withExistingRepository[A](repoName: String)(f: RepositoryDetails => Future[ValidatedNel[Error, A]])(
    implicit hc: HeaderCarrier): Future[ValidatedNel[Error, A]] =
    teamsAndRepositoriesConnector.getRepositoryDetails(repoName).flatMap {
      case Some(repoDetails) => f(repoDetails)
      case None              => Future.successful(RepositoryNotFound(repoName).invalidNel)
    }

  private def withExistingTeams[A](repoName: String, repositoryDetails: RepositoryDetails)(
    f: NonEmptyList[String] => Future[ValidatedNel[Error, A]])(
    implicit hc: HeaderCarrier): Future[ValidatedNel[Error, A]] =
    repositoryDetails.teamNames.toNel match {
      case Some(teamNames) => f(teamNames)
      case None            => Future.successful(TeamsNotFoundForRepository(repoName).invalidNel)
    }

  private def withExistingSlackChannel[A](teamName: String)(f: String => Future[ValidatedNel[Error, A]])(
    implicit hc: HeaderCarrier): Future[ValidatedNel[Error, A]] =
    userManagementConnector
      .getTeamSlackChannel(teamName)
      .map { r =>
        extractSlackChannel(r.json)
      }
      .flatMap {
        case Some(slackChannel) => f(slackChannel)
        case None               => Future.successful(SlackChannelNotFoundForTeam(teamName).invalidNel)
      }

  private def flatten[E, A](xs: NonEmptyList[ValidatedNel[E, A]]): ValidatedNel[E, A] =
    xs.reduceLeft { (acc, c) =>
      c match {
        case Valid(_) => acc
        case Invalid(errors) =>
          acc match {
            case Valid(_) => c
            case Invalid(errorsSoFar) =>
              errorsSoFar.concatNel(errors).invalid
          }
      }
    }

  private[services] def sendSlackMessage(slackMessage: SlackMessage)(
    implicit hc: HeaderCarrier): Future[Either[SlackError, Unit]] =
    slackConnector.sendMessage(slackMessage).map { response =>
      response.status match {
        case 200 => Right(())
        case _ =>
          Logger.warn(s"Slack API returned error, status=${response.status} and body='${response.body}'")
          Left(SlackError(response.status, response.body))
      }
    }

  private[services] def extractSlackChannel(json: JsValue): Option[String] =
    for {
      js           <- Option(json)
      teamDetails  <- js.asOpt[TeamDetails]
      slackChannel <- teamDetails.slackChannel
    } yield slackChannel

  // helps avoiding many concurrent requests
  private def traverseFuturesSequentially[A, B](as: NonEmptyList[A])(f: A => Future[B])(
    implicit ec: ExecutionContext): Future[NonEmptyList[B]] =
    as.tail
      .foldLeft(f(as.head).map(v => NonEmptyList.of(v))) { (accF, current) =>
        for {
          acc <- accF
          c   <- f(current)
        } yield {
          c :: acc
        }
      }
      .map(_.reverse)
}

object NotificationService {

  sealed trait Error extends Product with Serializable {
    def message: String
    override def toString = message
  }

  final case class SlackError(statusCode: Int, slackErrorMsg: String) extends Error {
    def message = s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg'"
  }
  final case class RepositoryNotFound(repoName: String) extends Error {
    def message = s"Repository: '$repoName' not found"
  }
  final case class TeamsNotFoundForRepository(repoName: String) extends Error {
    def message = s"Team not found for repository: '$repoName"
  }
  final case class SlackChannelNotFoundForTeam(teamName: String) extends Error {
    def message = s"Slack channel not found for team: '$teamName'"
  }

  case class TeamDetails(slack: String) {
    def slackChannel: Option[String] = {
      val slashPos = slack.lastIndexOf("/")
      val s        = slack.substring(slashPos + 1)
      if (slashPos > 0 && s.nonEmpty) Some(s) else None
    }
  }

  object TeamDetails {
    implicit val format: Format[TeamDetails] = Json.format[TeamDetails]
  }
}
