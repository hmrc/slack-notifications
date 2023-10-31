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
import uk.gov.hmrc.http._
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.{DomainConfig, SlackConfig}
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.connectors.SlackConnector
import uk.gov.hmrc.slacknotifications.model._
import uk.gov.hmrc.slacknotifications.services.AuthService.ClientService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class LegacyNotificationService @Inject()(
  slackNotificationConfig: SlackNotificationConfig,
  slackConnector         : SlackConnector,
  slackConfig            : SlackConfig,
  domainConfig           : DomainConfig,
  userManagementService  : UserManagementService,
  channelLookupService   : ChannelLookupService
)(implicit
  ec: ExecutionContext
) extends Logging {

  def sendNotification(notificationRequest: NotificationRequest, clientService: ClientService)(
    implicit hc: HeaderCarrier): Future[NotificationResult] =
    notificationRequest.channelLookup match {

      case ChannelLookup.GithubRepository(name) =>
        {
          for {
            repositoryDetails         <- EitherT(channelLookupService.getExistingRepository(name))
            allTeams                  <- EitherT(channelLookupService.getTeamsResponsibleForRepo(name, repositoryDetails))
            (excluded, toBeProcessed)  = allTeams.partition(slackNotificationConfig.notRealTeams.contains)
            notificationResult        <- EitherT.liftF[Future, NotificationResult, NotificationResult](toBeProcessed.foldLeftM(Seq.empty[NotificationResult]){(acc, teamName) =>
                for {
                  slackChannel    <- channelLookupService.getExistingSlackChannel(teamName)
                  notificationRes <- slackChannel match {
                    case Right(teamChannel)    =>  sendSlackMessage(fromNotification(notificationRequest, teamChannel), clientService, Some(teamName))
                    case Left(fallbackChannel) =>  sendSlackMessage(fromNotification(notificationRequest, fallbackChannel, Some(UnableToFindTeamSlackChannelInUMP(teamName).message)), clientService, Some(teamName)).map(_.copy(errors = Seq(UnableToFindTeamSlackChannelInUMP(teamName))))
                  }
                } yield acc :+ notificationRes
              }.map(concatResults))
            resWithExclusions          = notificationResult.addExclusion(excluded.map(NotARealTeam.apply): _*)
          } yield resWithExclusions
        }.merge

      case ChannelLookup.GithubTeam(team) =>
          channelLookupService.getExistingSlackChannel(team).flatMap{slackChannel =>
            slackChannel match {
              case Right(teamChannel)    =>  sendSlackMessage(fromNotification(notificationRequest, teamChannel), clientService, Some(team))
              case Left(fallbackChannel) =>  sendSlackMessage(fromNotification(notificationRequest, fallbackChannel, Some(UnableToFindTeamSlackChannelInUMP(team).message)), clientService, Some(team)).map(_.copy(errors = Seq(UnableToFindTeamSlackChannelInUMP(team))))
            }
          }

      case ChannelLookup.SlackChannel(slackChannels) =>
        slackChannels.toList.foldLeftM(Seq.empty[NotificationResult]){(acc, slackChannel) =>
          sendSlackMessage(fromNotification(notificationRequest, slackChannel), clientService).map(acc :+ _)
        }.map(concatResults)

      case ChannelLookup.TeamsOfGithubUser(githubUsername) =>
        if (slackNotificationConfig.notRealGithubUsers.contains(githubUsername))
          Future.successful(NotificationResult().addExclusion(NotARealGithubUser(githubUsername)))
        else
          sendNotificationForUser("github", githubUsername, userManagementService.getTeamsForGithubUser)(notificationRequest, clientService)

      case ChannelLookup.TeamsOfLdapUser(ldapUsername) =>
          sendNotificationForUser("ldap", ldapUsername, userManagementService.getTeamsForLdapUser)(notificationRequest, clientService)
    }

  private def sendNotificationForUser(
    userType   : String,
    username   : String,
    teamsGetter: String => Future[List[TeamName]]
  )(
    notificationRequest: NotificationRequest,
    service            : ClientService
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
                                        case Right(teamChannel)    =>  sendSlackMessage(fromNotification(notificationRequest, teamChannel), service, Some(teamName))
                                        case Left(fallbackChannel) =>  sendSlackMessage(fromNotification(notificationRequest, fallbackChannel, Some(UnableToFindTeamSlackChannelInUMP(teamName).message)), service, Some(teamName)).map(_.copy(errors = Seq(UnableToFindTeamSlackChannelInUMP(teamName))))
                                      }
                                    } yield acc :+ notificationRes
                                  }}.map(concatResults)
                                  else {
                                    logger.info(s"Failed to find teams for usertype: $userType, username: $username. " +
                                      s"Sending slack notification to Platops admin channel instead")
                                    sendSlackMessage(
                                      LegacySlackMessage.sanitise(
                                        LegacySlackMessage(
                                          channel = slackConfig.noTeamFoundAlert.channel,
                                          text = slackConfig.noTeamFoundAlert.text.replace("{service}", service.name),
                                          username = slackConfig.noTeamFoundAlert.username,
                                          icon_emoji = Some(slackConfig.noTeamFoundAlert.iconEmoji),
                                          attachments = Seq(
                                            Attachment(
                                              fields = Some(List(
                                                Attachment.Field(title = "Error", value = TeamsNotFoundForUsername(userType, username).stylisedMessage, short = false),
                                                Attachment.Field(title = "Message Details", value = notificationRequest.messageDetails.text, short = false),
                                              ))
                                            )
                                          ) ++ notificationRequest.messageDetails.attachments,
                                          showAttachmentAuthor = false
                                        ),
                                        domainConfig
                                      ),
                                      service = service
                                    )
                                    Future.successful(NotificationResult().addError(TeamsNotFoundForUsername(userType, username)))
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

  private def fromNotification(notificationRequest: NotificationRequest, slackChannel: String, errorMessage: Option[String] = None): LegacySlackMessage = {
    import notificationRequest.messageDetails._
    // username and user emoji are initially hard-coded to 'slack-notifications' and none,
    // then overridden from the authorised services config later
    errorMessage match {
      case Some(error) => LegacySlackMessage.sanitise(LegacySlackMessage(slackChannel, error, "slack-notifications", None, Attachment(text = Some(text)) +: attachments, showAttachmentAuthor), domainConfig)
      case None        => LegacySlackMessage.sanitise(LegacySlackMessage(slackChannel, text, "slack-notifications", None, attachments, showAttachmentAuthor), domainConfig)
    }
  }

  // Override the username used to send the message to what is configured in the config for the sending service
  def populateNameAndIconInMessage(slackMessage: LegacySlackMessage, service: ClientService): LegacySlackMessage = {
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
                                          slackMessage: LegacySlackMessage,
                                          service     : ClientService,
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

  private def slackMessageToString(slackMessage: LegacySlackMessage): String =
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

}
