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

package uk.gov.hmrc.slacknotifications.services

import cats.implicits._

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.Logging
import uk.gov.hmrc.http._
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.{Attachment, ChannelLookup, NotificationRequest, SlackMessage}
import uk.gov.hmrc.slacknotifications.services.AuthService.Service

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class NotificationService @Inject()(
  slackNotificationConfig      : SlackNotificationConfig,
  slackConnector               : SlackConnector,
  slackConfig                  : SlackConfig,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  userManagementConnector      : UserManagementConnector,
  userManagementService        : UserManagementService
)(implicit
  ec: ExecutionContext
) extends Logging {

  import NotificationService._

  def sendNotification(notificationRequest: NotificationRequest, service: Service)(
    implicit hc: HeaderCarrier): Future[NotificationResult] =
    notificationRequest.channelLookup match {

      case ChannelLookup.GithubRepository(name) =>
        withExistingRepository(name) { repositoryDetails =>
          withTeamsResponsibleForRepo(name, repositoryDetails) { teamNames =>
            traverseFuturesSequentially(teamNames) { teamName =>
              withExistingSlackChannel(teamName) { slackChannel =>
                sendSlackMessage(fromNotification(notificationRequest, slackChannel), service, Some(teamName))
              }
            }.map(flatten)
          }
        }

      case ChannelLookup.SlackChannel(slackChannels) =>
        traverseFuturesSequentially(slackChannels.toList) { slackChannel =>
          sendSlackMessage(fromNotification(notificationRequest, slackChannel), service)
        }.map(flatten)

      case ChannelLookup.TeamsOfGithubUser(githubUsername) =>
        if (slackNotificationConfig.notRealGithubUsers.contains(githubUsername))
          Future.successful(NotificationResult().addExclusion(NotARealGithubUser(githubUsername)))
        else
          sendNotificationForUser("github", githubUsername, userManagementService.getTeamsForGithubUser)(notificationRequest, service)

      case ChannelLookup.TeamsOfLdapUser(ldapUsername) =>
          sendNotificationForUser("ldap", ldapUsername, userManagementService.getTeamsForLdapUser)(notificationRequest, service)
    }

  private def sendNotificationForUser(
    userType   : String,
    username   : String,
    teamsGetter: String => Future[List[TeamDetails]]
  )(
    notificationRequest: NotificationRequest,
    service            : Service
  )(
    implicit
    hc: HeaderCarrier
  ): Future[NotificationResult] = {
    teamsGetter(username).flatMap { allTeams =>
      withNonExcludedTeams(allTeams.map(_.team)) { nonExcludedTeams =>
        if (nonExcludedTeams.nonEmpty) {
          traverseFuturesSequentially(nonExcludedTeams) { teamName =>
            withExistingSlackChannel(teamName) { slackChannel =>
              sendSlackMessage(fromNotification(notificationRequest, slackChannel), service)
            }
          }.map(flatten)
        } else {
          sendSlackMessage(SlackMessage(
            channel     = slackConfig.noTeamFoundAlert.channel,
            text        = slackConfig.noTeamFoundAlert.text.replace("{user}", userType),
            username    = slackConfig.noTeamFoundAlert.username,
            icon_emoji  = Some(slackConfig.noTeamFoundAlert.iconEmoji),
            attachments = Seq(Attachment(title = Some(notificationRequest.messageDetails.text))), showAttachmentAuthor = true),
            service     = service
          )
          Future.successful(NotificationResult().addError(TeamsNotFoundForUsername(userType, username)))
        }
      }
    }
  }

  private def flatten(results: Seq[NotificationResult]): NotificationResult =
    results.foldLeft(NotificationResult())((acc, current) =>
      acc
        .addSuccessfullySent(current.successfullySentTo: _*)
        .addError(current.errors: _*)
        .addExclusion(current.exclusions: _*)
    )

  private def fromNotification(notificationRequest: NotificationRequest, slackChannel: String): SlackMessage = {
    import notificationRequest.messageDetails._
    // username and user emoji are initially hard-coded to 'slack-notifications' and none,
    // then overridden from the authorised services config later
    SlackMessage.sanitise(SlackMessage(slackChannel, text, "slack-notifications", None, attachments, showAttachmentAuthor))
  }

  private def withExistingRepository[A](
    repoName: String
  )(
    f: RepositoryDetails => Future[NotificationResult]
  )(implicit
    hc: HeaderCarrier
  ): Future[NotificationResult] =
    teamsAndRepositoriesConnector
      .getRepositoryDetails(repoName)
      .flatMap {
        case Some(repoDetails) => f(repoDetails)
        case None              => Future.successful(NotificationResult().addError(RepositoryNotFound(repoName)))
      }

  private def withTeamsResponsibleForRepo[A](
    repoName         : String,
    repositoryDetails: RepositoryDetails
  )(
    f: List[String] => Future[NotificationResult]
  ): Future[NotificationResult] =
    getTeamsResponsibleForRepo(repositoryDetails) match {
      case Nil   => Future.successful(NotificationResult().addError(TeamsNotFoundForRepository(repoName)))
      case teams => withNonExcludedTeams(teams)(f)
    }

  private[services] def getTeamsResponsibleForRepo(repositoryDetails: RepositoryDetails): List[String] =
    if (repositoryDetails.owningTeams.nonEmpty)
      repositoryDetails.owningTeams
    else
      repositoryDetails.teamNames

  def withNonExcludedTeams(
    allTeamNames: List[String]
  )(
    f: List[String] => Future[NotificationResult]
  )(implicit
    ec: ExecutionContext
  ): Future[NotificationResult] = {
    val (excluded, toBeProcessed) = allTeamNames.partition(slackNotificationConfig.notRealTeams.contains)
    f(toBeProcessed)
      .map(_.addExclusion(excluded.map(NotARealTeam.apply): _*))
  }

  private def withExistingSlackChannel(
    teamName: String
  )(
    f: String => Future[NotificationResult]
  )(implicit
    hc: HeaderCarrier
  ): Future[NotificationResult] =
    userManagementConnector
      .getTeamDetails(teamName)
      .map(_.flatMap(extractSlackChannel))
      .flatMap {
        case Some(slackChannel) => f(slackChannel)
        case None               => f(predictedTeamName(teamName))
      }

  private def predictedTeamName(teamName: String): String = "team-" + teamName.replace(" ", "-").toLowerCase

  private[services] def extractSlackChannel(teamDetails: TeamDetails): Option[String] =
    teamDetails.slackNotification.orElse(teamDetails.slack).flatMap { slackChannelUrl =>
      val urlWithoutTrailingSpace =
        if (slackChannelUrl.endsWith("/"))
          slackChannelUrl.init
        else
          slackChannelUrl

      val slashPos = urlWithoutTrailingSpace.lastIndexOf("/")
      val s        = urlWithoutTrailingSpace.substring(slashPos + 1)
      if (slashPos > 0 && s.nonEmpty) Some(s) else None
    }

  // Override the username used to send the message to what is configured in the config for the sending service
  def populateNameAndIconInMessage(slackMessage: SlackMessage, service: Service): SlackMessage = {
    val config      = slackNotificationConfig.serviceConfigs.find(_.name == service.name)
    val displayName = config.flatMap(_.displayName).getOrElse(service.name)

    slackMessage.copy(
      username    = displayName,
      icon_emoji  = config.flatMap(_.userEmoji),
      attachments = slackMessage.attachments.map(_.copy(
                      author_name = if(slackMessage.showAttachmentAuthor) Some(displayName) else None,
                      author_icon = None
                    ))
    )
  }

  private[services] def sendSlackMessage(
    slackMessage: SlackMessage,
    service     : Service,
    teamName    : Option[String] = None
  )(implicit
    hc: HeaderCarrier
  ): Future[NotificationResult] =
    if (slackNotificationConfig.notificationEnabled)
      slackConnector
        .sendMessage(populateNameAndIconInMessage(slackMessage, service))
        .map { response =>
          response.status match {
            case 200 => NotificationResult().addSuccessfullySent(slackMessage.channel)
            case _ => logAndReturnSlackError(response.status, response.body, slackMessage.channel, teamName)
          }
        }
        .recoverWith(handleSlackExceptions(slackMessage.channel, teamName))
    else
      Future.successful {
        val messageStr = slackMessageToString(populateNameAndIconInMessage(slackMessage, service))
        NotificationResult().addExclusion(NotificationDisabled(messageStr))
      }

  private def slackMessageToString(slackMessage: SlackMessage): String =
    s"""
       |   Channel: ${slackMessage.channel}
       |   Message: ${slackMessage.text}
       |   Username: ${slackMessage.username}
       |   Emoji: ${slackMessage.icon_emoji.getOrElse("")}
       |""".stripMargin

  private def handleSlackExceptions(channel: String, teamName: Option[String]): PartialFunction[Throwable, Future[NotificationResult]] = {
    case ex @ UpstreamErrorResponse.WithStatusCode(404) if ex.message.contains("channel_not_found") =>
      handleChannelNotFound(channel)
    case UpstreamErrorResponse.Upstream4xxResponse(ex) =>
      Future.successful(logAndReturnSlackError(ex.statusCode, ex.message, channel, teamName))
    case UpstreamErrorResponse.Upstream5xxResponse(ex) =>
      Future.successful(logAndReturnSlackError(ex.statusCode, ex.message, channel, teamName))
    case NonFatal(ex) =>
      logger.error(s"Unable to notify Slack channel $channel", ex)
      Future.failed(ex)
  }

  private def handleChannelNotFound(channel: String): Future[NotificationResult] = {
    logger.error(SlackChannelNotFound(channel).message)
    Future.successful(NotificationResult().addError(SlackChannelNotFound(channel)))
  }

  private def logAndReturnSlackError(statusCode: Int, exceptionMessage: String, channel: String, teamName: Option[String]): NotificationResult = {
    val slackError = SlackError(statusCode, exceptionMessage, channel, teamName)
    logger.error(s"Unable to notify Slack channel $channel, the following error occurred: ${slackError.message}")
    NotificationResult().addError(slackError)
  }

  // helps avoiding too many concurrent requests
  private def traverseFuturesSequentially[A, B](
    as         : Seq[A],
    parallelism: Int = 5
  )(
    f: A => Future[B]
  )(implicit
    ec: ExecutionContext
  ): Future[Seq[B]] =
    as
      .grouped(parallelism)
      .toList
      .foldLeftM(List.empty[B])((acc, grouped) =>
        grouped.toList.traverse(f).map(acc ++ _)
      )
}

object NotificationService {

  sealed trait Error extends Product with Serializable {
    def code: String

    def message: String

    override def toString = message
  }

  object Error {
    implicit val writes: Writes[Error] = Writes { error =>
      Json.obj(
        "code"    -> error.code,
        "message" -> error.message
      )
    }
  }

  final case class SlackError(statusCode: Int, slackErrorMsg: String, channel: String, teamName: Option[String]) extends Error {
    val code    = "slack_error"
    val message = teamName match {
      case Some(value) =>  s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel', team: '$value'"
      case None =>  s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel'"
    }
  }

  final case class RepositoryNotFound(repoName: String) extends Error {
    val code    = "repository_not_found"
    val message = s"Repository: '$repoName' not found"
  }

  final case class TeamsNotFoundForRepository(repoName: String) extends Error {
    val code    = "teams_not_found_for_repository"
    val message = s"Teams not found for repository: '$repoName'"
  }

  final case class TeamsNotFoundForUsername(userType: String, username: String) extends Error {
    val code    = s"teams_not_found_for_${userType.toLowerCase}_username"
    val message = s"Teams not found for ${userType.capitalize} username: '$username'"
  }

  final case class SlackChannelNotFound(channelName: String) extends Error {
    val code    = "slack_channel_not_found"
    val message = s"Slack channel: '$channelName' not found"
  }

  sealed trait Exclusion extends Product with Serializable {
    def code: String

    def message: String

    override def toString = message
  }

  object Exclusion {
    implicit val writes: Writes[Exclusion] = Writes { exclusion =>
      Json.obj(
        "code"    -> exclusion.code,
        "message" -> exclusion.message
      )
    }
  }

  final case class NotARealTeam(name: String) extends Exclusion {
    val code    = "not_a_real_team"
    val message = s"$name is not a real team"
  }

  final case class NotARealGithubUser(name: String) extends Exclusion {
    val code    = "not_a_real_github_user"
    val message = s"$name is not a real Github user"
  }

  final case class NotificationDisabled(slackMessage: String) extends Exclusion {
    val code    = "notification_disabled"
    val message = s"Slack notifications have been disabled. Slack message: $slackMessage"
  }

  final case class NotificationResult(
    successfullySentTo: Seq[String]    = Nil,
    errors            : Seq[Error]     = Nil,
    exclusions        : Seq[Exclusion] = Nil
  ) {
    def addError(e: Error*): NotificationResult =
      copy(errors = errors ++ e)

    def addSuccessfullySent(s: String*): NotificationResult =
      copy(successfullySentTo = successfullySentTo ++ s)

    def addExclusion(e: Exclusion*): NotificationResult =
      copy(exclusions = exclusions ++ e)
  }

  object NotificationResult {
    implicit val writes: OWrites[NotificationResult] = Json.writes[NotificationResult]
  }

}
