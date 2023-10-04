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

import cats.data.EitherT
import cats.implicits._
import play.api.Logging
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.connectors.SlackConnector
import uk.gov.hmrc.slacknotifications.controllers.v2.NotificationController.SendNotificationRequest
import uk.gov.hmrc.slacknotifications.model._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class NotificationService @Inject()(
  slackNotificationConfig: SlackNotificationConfig
, slackConfig            : SlackConfig
, slackConnector         : SlackConnector
, userManagementService  : UserManagementService
, channelLookupService   : ChannelLookupService
)(implicit
  ec: ExecutionContext
) extends Logging {

  def sendNotification(request: SendNotificationRequest)(implicit hc: HeaderCarrier): Future[NotificationResult] =
    request.channelLookup match {
      case ChannelLookup.GithubRepository(name) =>
        {
          for {
            repositoryDetails         <- EitherT(channelLookupService.getExistingRepository(name))
            allTeams                  <- EitherT(channelLookupService.getTeamsResponsibleForRepo(name, repositoryDetails))
            (excluded, toBeProcessed)  = allTeams.partition(slackNotificationConfig.notRealTeams.contains)
            notificationResult        <- EitherT.liftF[Future, NotificationResult, NotificationResult](toBeProcessed.foldLeftM(Seq.empty[NotificationResult]) { (acc, teamName) =>
                for {
                  slackChannel    <- channelLookupService.getExistingSlackChannel(teamName)
                  notificationRes <- slackChannel match {
                    case Right(teamChannel)    => sendSlackMessage(createMessageFromRequest(request, teamChannel), Some(teamName))
                    case Left(fallbackChannel) => sendSlackMessage(createMessageFromRequest(request, fallbackChannel, Some(UnableToFindTeamSlackChannelInUMP(teamName).message)), Some(teamName)).map(_.copy(errors = Seq(UnableToFindTeamSlackChannelInUMP(teamName))))
                  }
                } yield acc :+ notificationRes
              }.map(concatResults))
            resWithExclusions          = notificationResult.addExclusion(excluded.map(NotARealTeam.apply): _*)
          } yield resWithExclusions
        }.merge

      case ChannelLookup.GithubTeam(team) =>
        channelLookupService.getExistingSlackChannel(team).flatMap { slackChannel =>
          slackChannel match {
            case Right(teamChannel)    => sendSlackMessage(createMessageFromRequest(request, teamChannel), Some(team))
            case Left(fallbackChannel) => sendSlackMessage(createMessageFromRequest(request, fallbackChannel, Some(UnableToFindTeamSlackChannelInUMP(team).message)), Some(team)).map(_.copy(errors = Seq(UnableToFindTeamSlackChannelInUMP(team))))
          }
        }

      case ChannelLookup.SlackChannel(slackChannels) =>
        slackChannels.toList.foldLeftM(Seq.empty[NotificationResult]) { (acc, slackChannel) =>
          sendSlackMessage(createMessageFromRequest(request, slackChannel)).map(acc :+ _)
        }.map(concatResults)

      case ChannelLookup.TeamsOfGithubUser(githubUsername) =>
        if (slackNotificationConfig.notRealGithubUsers.contains(githubUsername))
          Future.successful(NotificationResult().addExclusion(NotARealGithubUser(githubUsername)))
        else
          sendNotificationForUser("github", githubUsername, userManagementService.getTeamsForGithubUser)(request)

      case ChannelLookup.TeamsOfLdapUser(ldapUsername) =>
        sendNotificationForUser("ldap", ldapUsername, userManagementService.getTeamsForLdapUser)(request)
    }

  private def sendNotificationForUser(
    userType: String,
    username: String,
    teamsGetter: String => Future[List[TeamName]]
  )(
    request: SendNotificationRequest
  )(
    implicit
    hc: HeaderCarrier
  ): Future[NotificationResult] =
    for {
      allTeams                  <- teamsGetter(username)
      (excluded, toBeProcessed)  = allTeams.map(_.asString).partition(slackNotificationConfig.notRealTeams.contains)
      notificationResult        <- if (toBeProcessed.nonEmpty) {
                                     toBeProcessed.foldLeftM(Seq.empty[NotificationResult]) { (acc, teamName) =>
                                       for {
                                         slackChannel    <- channelLookupService.getExistingSlackChannel(teamName)
                                         notificationRes <- slackChannel match {
                                           case Right(teamChannel)    => sendSlackMessage(createMessageFromRequest(request, teamChannel), Some(teamName))
                                           case Left(fallbackChannel) => sendSlackMessage(createMessageFromRequest(request, fallbackChannel, Some(UnableToFindTeamSlackChannelInUMP(teamName).message)), Some(teamName)).map(_.copy(errors = Seq(UnableToFindTeamSlackChannelInUMP(teamName))))
                                         }
                                       } yield acc :+ notificationRes
                                     }
                                   }.map(concatResults)
                                   else {
                                     logger.info(s"Failed to find teams for usertype: $userType, username: $username. " +
                                                 s"Sending slack notification to Platops admin channel instead")

                                     val fallbackChannel = slackConfig.noTeamFoundAlert.channel
                                     val error           = TeamsNotFoundForUsername(userType, username)

                                     sendSlackMessage(createMessageFromRequest(request, fallbackChannel, Some(error.stylisedMessage)))

                                     Future.successful(NotificationResult().addError(error))
                                   }
      resWithExclusions          = notificationResult.addExclusion(excluded.map(NotARealTeam.apply): _*)
    } yield resWithExclusions

  private def concatResults(results: Seq[NotificationResult]): NotificationResult =
    results.foldLeft(NotificationResult())((acc, current) =>
      acc
        .addSuccessfullySent(current.successfullySentTo: _*)
        .addError(current.errors: _*)
        .addExclusion(current.exclusions: _*)
    )

  private def createMessageFromRequest(
    request     : SendNotificationRequest,
    slackChannel: String,
    errorMessage: Option[String] = None
  ): SlackMessage =
    errorMessage match {
      case Some(error) =>
        SlackMessage.sanitise(
          SlackMessage(
            channel  = slackChannel,
            text     = error,
            blocks   = Seq(SlackMessage.errorBlock(error), SlackMessage.divider) ++ request.blocks,
            username = request.displayName,
            emoji    = request.emoji
          )
        )
      case None =>
        SlackMessage.sanitise(
          SlackMessage(
            channel  = slackChannel,
            text     = request.text,
            blocks   = request.blocks,
            username = request.displayName,
            emoji    = request.emoji
          )
        )
    }

  private[services] def sendSlackMessage(
    slackMessage: SlackMessage,
    teamName: Option[String] = None
  )(implicit
    hc: HeaderCarrier
  ): Future[NotificationResult] =
    if (slackNotificationConfig.notificationEnabled)
      slackConnector
        .chatPostMessage(slackMessage)
        .map { response =>
          response.status match {
            case 200 => NotificationResult().addSuccessfullySent(slackMessage.channel)
            case _ => logAndReturnSlackError(response.status, response.body, slackMessage.channel, teamName)
          }
        }
        .recoverWith(handleSlackExceptions(slackMessage.channel, teamName))
    else
      Future.successful {
        val messageStr = slackMessageToString(slackMessage)
        NotificationResult().addExclusion(NotificationDisabled(messageStr))
      }

  private def slackMessageToString(slackMessage: SlackMessage): String =
    s"""
       |   Channel: ${slackMessage.channel}
       |   Message: ${slackMessage.text}
       |   # of Blocks: ${slackMessage.blocks.length}
       |   Username: ${slackMessage.username}
       |   Emoji: ${slackMessage.emoji}
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
}
