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
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.{DomainConfig, SlackConfig}
import uk.gov.hmrc.slacknotifications.connectors.SlackConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.controllers.v2.NotificationController.SendNotificationRequest
import uk.gov.hmrc.slacknotifications.model._
import uk.gov.hmrc.slacknotifications.persistence.SlackMessageQueueRepository

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class NotificationService @Inject()(
  slackNotificationConfig: SlackNotificationConfig
, slackConfig            : SlackConfig
, domainConfig           : DomainConfig
, userManagementService  : UserManagementService
, channelLookupService   : ChannelLookupService
, slackMessageQueue      : SlackMessageQueueRepository
, slackConnector         : SlackConnector
)(implicit
  ec: ExecutionContext
) extends Logging {

  def sendNotification(request: SendNotificationRequest)(implicit hc: HeaderCarrier): EitherT[Future, NotificationResult, SendNotificationResponse] = {
    val msgId: UUID = UUID.randomUUID()

    handleRequest(msgId, request)
      .map(_ => SendNotificationResponse(msgId))
  }

  private def handleRequest(msgId: UUID, request: SendNotificationRequest)(implicit hc: HeaderCarrier): EitherT[Future, NotificationResult, Unit] =
    request.channelLookup match {
      case ChannelLookup.GithubRepository(name) =>
        for {
          repositoryDetails <- EitherT(channelLookupService.getExistingRepository(name))
          allTeams          <- EitherT(channelLookupService.getTeamsResponsibleForRepo(name, repositoryDetails))
          (excluded, toDo)  =  allTeams.partition(slackNotificationConfig.notRealTeams.contains)
          initResult        =  NotificationResult().addExclusion(excluded.map(Exclusion.notARealTeam): _*)
          _                 <- EitherT.liftF[Future, NotificationResult, Seq[Unit]](toDo.foldLeftM(Seq.empty[Unit]) { (acc, teamName) =>
                                 for {
                                   slackChannel <- channelLookupService.getExistingSlackChannel(teamName)
                                   _            <- slackChannel match {
                                                     case Right(teamChannel)    =>
                                                       queueSlackMessage(msgId, createMessageFromRequest(request, teamChannel), initResult)
                                                     case Left(fallbackChannel) =>
                                                       val error = Error.unableToFindTeamSlackChannelInUMP(teamName)
                                                       queueSlackMessage(msgId, createMessageFromRequest(request, fallbackChannel, Some(error.message)), initResult.addError(error))
                                                   }
                                 } yield acc :+ ()
                               })
        } yield ()
      case ChannelLookup.GithubTeam(team) =>
        EitherT.liftF[Future, NotificationResult, Unit](
          channelLookupService.getExistingSlackChannel(team).flatMap {
            case Right(teamChannel) =>
              queueSlackMessage(msgId, createMessageFromRequest(request, teamChannel), NotificationResult())
            case Left(fallbackChannel) =>
              val error = Error.unableToFindTeamSlackChannelInUMP(team)
              queueSlackMessage(msgId, createMessageFromRequest(request, fallbackChannel, Some(error.message)), NotificationResult().addError(error))
          }
        )
      case ChannelLookup.SlackChannel(slackChannels) =>
        EitherT.liftF(
          slackChannels.toList.foldLeftM(Seq.empty[Unit]) { (acc, slackChannel) =>
            queueSlackMessage(msgId, createMessageFromRequest(request, slackChannel), NotificationResult())
              .map(acc :+ _)
          }.map(_ => ())
        )
      case ChannelLookup.TeamsOfGithubUser(githubUsername) =>
        if (slackNotificationConfig.notRealGithubUsers.contains(githubUsername))
          EitherT.leftT[Future, Unit](NotificationResult().addExclusion(Exclusion.notARealGithubUser(githubUsername)))
        else
          EitherT.liftF(
            handleRequestForUser("github", githubUsername, userManagementService.getTeamsForGithubUser)(msgId, request)
          )
      case ChannelLookup.TeamsOfLdapUser(ldapUsername) =>
        EitherT.liftF(
          handleRequestForUser("ldap", ldapUsername, userManagementService.getTeamsForLdapUser)(msgId, request)
        )
    }

  private def handleRequestForUser(
    userType   : String,
    username   : String,
    teamsGetter: String => Future[List[TeamName]]
  )(
    msgId  : UUID,
    request: SendNotificationRequest
  )(implicit
    hc: HeaderCarrier
  ): Future[Unit] =
    for {
      allTeams         <- teamsGetter(username)
      (excluded, toDo) =  allTeams.map(_.asString).partition(slackNotificationConfig.notRealTeams.contains)
      initResult       =  NotificationResult().addExclusion(excluded.map(Exclusion.notARealTeam): _*)
      _                <- if(toDo.nonEmpty) {
                            toDo.foldLeftM(Seq.empty[Unit]){ (acc, teamName) =>
                              for {
                                slackChannel <- channelLookupService.getExistingSlackChannel(teamName)
                                _            <- slackChannel match {
                                                  case Right(teamChannel)    =>
                                                    queueSlackMessage(msgId, createMessageFromRequest(request, teamChannel), initResult)
                                                  case Left(fallbackChannel) =>
                                                    val error = Error.unableToFindTeamSlackChannelInUMP(teamName)
                                                    queueSlackMessage(msgId, createMessageFromRequest(request, fallbackChannel, Some(error.message)), initResult.addError(error))
                                                }
                              } yield acc :+ ()
                            }
                          } else {
                            logger.info(s"Failed to find teams for usertype: $userType, username: $username. " +
                              s"Sending slack notification to Platops admin channel instead")

                            val fallbackChannel = slackConfig.noTeamFoundAlert.channel
                            val error = Error.teamsNotFoundForUsername(userType, username)

                            queueSlackMessage(msgId, createMessageFromRequest(request, fallbackChannel, Some(error.message)), initResult.addError(error))
                          }
    } yield ()

  private def createMessageFromRequest(
    request     : SendNotificationRequest,
    slackChannel: String,
    errorMessage: Option[String] = None
  ): SlackMessage =
    errorMessage match {
      case Some(error) =>
        SlackMessage.sanitise(
          SlackMessage(
            channel     = slackChannel,
            text        = error,
            blocks      = Seq(SlackMessage.errorBlock(error), SlackMessage.divider) ++ request.blocks,
            attachments = request.attachments,
            username    = request.displayName,
            emoji       = request.emoji
          ),
          domainConfig
        )
      case None =>
        SlackMessage.sanitise(
          SlackMessage(
            channel     = slackChannel,
            text        = request.text,
            blocks      = request.blocks,
            attachments = request.attachments,
            username    = request.displayName,
            emoji       = request.emoji
          ),
          domainConfig
        )
    }

  private[services] def queueSlackMessage(
    msgId       : UUID,
    slackMessage: SlackMessage,
    init        : NotificationResult
  ): Future[Unit] = {
    val queuedSlackMessage =
      QueuedSlackMessage(
        msgId        = msgId,
        slackMessage = slackMessage,
        result       = init
      )

    slackMessageQueue
      .add(queuedSlackMessage)
      .map { workItemId =>
        logger.info(s"Message with work item id: ${workItemId.toString} queued for request with msgId: ${msgId.toString}")
      }
  }

  def processMessageFromQueue(workItem: WorkItem[QueuedSlackMessage])(implicit hc: HeaderCarrier): Future[Unit] =
    slackConnector
      .postChatMessage(workItem.item.slackMessage)
      .flatMap { _ =>
        for {
          _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addSuccessfullySent(workItem.item.slackMessage.channel))
          _ <- slackMessageQueue.markSuccess(workItem.id)
        } yield ()
      }
      .recoverWith {
        case ex @ UpstreamErrorResponse.WithStatusCode(404) if ex.message.contains("channel_not_found") =>
          for {
            _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addError(Error.slackChannelNotFound(workItem.item.slackMessage.channel)))
            _ <- slackMessageQueue.markPermFailed(workItem.id)
          } yield ()
        case UpstreamErrorResponse.WithStatusCode(429) =>
          logger.warn(s"Received 429 when attempting to notify Slack channel ${workItem.item.slackMessage.channel} - queued for retry...")
          slackMessageQueue.markFailed(workItem.id).map(_ => ())
        case UpstreamErrorResponse.Upstream4xxResponse(ex) =>
          val error = Error.slackError(ex.statusCode, ex.message, workItem.item.slackMessage.channel, None)
          logger.error(s"Unable (4xx) to notify Slack channel ${workItem.item.slackMessage.channel}, the following error occurred: ${error.message}", ex)
          for {
            _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addError(error))
            _ <- slackMessageQueue.markPermFailed(workItem.id)
          } yield ()
        case UpstreamErrorResponse.Upstream5xxResponse(ex) =>
          val error = Error.slackError(ex.statusCode, ex.message, workItem.item.slackMessage.channel, None)
          logger.error(s"Unable (5xx) to notify Slack channel ${workItem.item.slackMessage.channel}, the following error occurred: ${error.message}")
          for {
            _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addError(error))
            _ <- slackMessageQueue.markFailed(workItem.id)
          } yield ()
        case NonFatal(ex) =>
          logger.error(s"Unable to notify Slack channel ${workItem.item.slackMessage.channel}", ex)
          Future.failed(ex)
      }
}
