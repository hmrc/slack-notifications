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
import play.api.libs.json._
import play.api.{Configuration, Logger}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.{ChannelLookup, NotificationRequest, SlackMessage}

class NotificationService @Inject()(
  configuration: Configuration,
  slackConnector: SlackConnector,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  userManagementConnector: UserManagementConnector) {
  import NotificationService._

  def sendNotification(notificationRequest: NotificationRequest)(
    implicit hc: HeaderCarrier): Future[NotificationResult] =
    notificationRequest.channelLookup match {

      case ChannelLookup.GithubRepository(_, name) =>
        withExistingRepository(name) { repositoryDetails =>
          withExistingTeams(name, repositoryDetails) { teamNames =>
            traverseFuturesSequentially(teamNames) { teamName =>
              withExistingSlackChannel(teamName) { slackChannel =>
                sendSlackMessage(fromNotification(notificationRequest, slackChannel))
              }
            }.map(flatten)
          }
        }

      case ChannelLookup.SlackChannel(_, slackChannels) =>
        traverseFuturesSequentially(slackChannels.toList) { slackChannel =>
          sendSlackMessage(fromNotification(notificationRequest, slackChannel))
        }.map(flatten)
    }

  private def flatten(results: Seq[NotificationResult]): NotificationResult =
    results.foldLeft(NotificationResult()) { (acc, current) =>
      acc
        .addSuccessfullySent(current.successfullySentTo: _*)
        .addError(current.errors: _*)
        .addExclusion(current.exclusions: _*)
    }

  private def fromNotification(notificationRequest: NotificationRequest, slackChannel: String): SlackMessage = {
    import notificationRequest.messageDetails._
    SlackMessage(slackChannel, text, username, iconEmoji, attachments)
  }

  private def withExistingRepository[A](repoName: String)(f: RepositoryDetails => Future[NotificationResult])(
    implicit hc: HeaderCarrier): Future[NotificationResult] =
    teamsAndRepositoriesConnector.getRepositoryDetails(repoName).flatMap {
      case Some(repoDetails) => f(repoDetails)
      case None              => Future.successful(NotificationResult().addError(RepositoryNotFound(repoName)))
    }

  private def withExistingTeams[A](repoName: String, repositoryDetails: RepositoryDetails)(
    f: List[String] => Future[NotificationResult])(implicit hc: HeaderCarrier): Future[NotificationResult] =
    repositoryDetails.teamNames match {
      case Nil => Future.successful(NotificationResult().addError(TeamsNotFoundForRepository(repoName)))
      case teams =>
        val (excluded, toBeProcessed) = teams.partition(notRealTeams.contains)
        f(toBeProcessed).map { res =>
          res.addExclusion(excluded.map(NotARealTeam.apply): _*)
        }
    }

  private val notRealTeams =
    configuration
      .getString("exclusions.notRealTeams")
      .map { v =>
        v.split(",").map(_.trim).toList
      }
      .getOrElse(Nil)

  private def withExistingSlackChannel[A](teamName: String)(f: String => Future[NotificationResult])(
    implicit hc: HeaderCarrier): Future[NotificationResult] =
    userManagementConnector
      .getTeamSlackChannel(teamName)
      .map { r =>
        extractSlackChannel(r.json)
      }
      .flatMap {
        case Some(slackChannel) => f(slackChannel)
        case None               => Future.successful(NotificationResult().addError(SlackChannelNotFoundForTeam(teamName)))
      }

  private[services] def sendSlackMessage(slackMessage: SlackMessage)(
    implicit hc: HeaderCarrier): Future[NotificationResult] =
    slackConnector.sendMessage(slackMessage).map { response =>
      response.status match {
        case 200 => NotificationResult().addSuccessfullySent(slackMessage.channel)
        case _ =>
          val slackError = SlackError(response.status, response.body)
          Logger.warn(slackError.message)
          NotificationResult().addError(slackError)
      }
    }

  private[services] def extractSlackChannel(json: JsValue): Option[String] =
    for {
      js           <- Option(json)
      teamDetails  <- js.asOpt[TeamDetails]
      slackChannel <- teamDetails.slackChannel
    } yield slackChannel

  // helps avoiding too many concurrent requests
  private def traverseFuturesSequentially[A, B](as: Seq[A], parallelism: Int = 5)(f: A => Future[B])(
    implicit ec: ExecutionContext): Future[Seq[B]] =
    as.grouped(parallelism).foldLeft(Future.successful(List.empty[B])) { (futAcc, grouped) =>
      for {
        acc <- futAcc
        b   <- Future.sequence(grouped.map(f))
      } yield {
        acc ++ b
      }
    }

}

object NotificationService {

  sealed trait Error extends Product with Serializable {
    def message: String
    override def toString = message
  }

  object Error {
    implicit val writes: Writes[Error] = Writes { error =>
      JsString(error.message)
    }
  }

  final case class SlackError(statusCode: Int, slackErrorMsg: String) extends Error {
    val message = s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg'"
  }
  final case class RepositoryNotFound(repoName: String) extends Error {
    val message = s"Repository: '$repoName' not found"
  }
  final case class TeamsNotFoundForRepository(repoName: String) extends Error {
    val message = s"Team not found for repository: '$repoName"
  }
  final case class SlackChannelNotFoundForTeam(teamName: String) extends Error {
    val message = s"Slack channel not found for team: '$teamName'"
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

  sealed trait Exclusion extends Product with Serializable {
    def message: String
    override def toString = message
  }

  object Exclusion {
    implicit val writes: Writes[Exclusion] = Writes { exclusion =>
      JsString(exclusion.message)
    }
  }

  final case class NotARealTeam(name: String) extends Exclusion {
    val message = s"$name is not a real team"
  }

  final case class NotificationResult(
    successfullySentTo: Seq[String] = Nil,
    errors: Seq[Error]              = Nil,
    exclusions: Seq[Exclusion]      = Nil
  ) {
    def addError(e: Error*): NotificationResult             = copy(errors             = errors ++ e)
    def addSuccessfullySent(s: String*): NotificationResult = copy(successfullySentTo = successfullySentTo ++ s)
    def addExclusion(e: Exclusion*): NotificationResult     = copy(exclusions         = exclusions ++ e)
  }

  object NotificationResult {
    implicit val writes: OWrites[NotificationResult] = Json.writes[NotificationResult]
  }
}
