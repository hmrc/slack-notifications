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
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.{DomainConfig, SlackConfig}
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.connectors.{RateLimitExceededException, ServiceConfigsConnector, SlackConnector, SlackError}
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
, serviceConfigsConnector: ServiceConfigsConnector
)(using ExecutionContext
) extends Logging:

  def getMessageStatus(msgId: UUID): Future[Option[NotificationStatus]] =
    slackMessageQueue.getByMsgId(msgId).map: workItems =>
      if workItems.nonEmpty then
        val finishedStates = Seq(ProcessingStatus.Succeeded, ProcessingStatus.PermanentlyFailed)

        val allStates = workItems.map(_.status).distinct

        if allStates.forall(finishedStates.contains) then
          Some(
            NotificationStatus(
              msgId = msgId,
              status = "complete",
              result = Some(NotificationResult.concatResults(workItems.map(_.item.result)))
            )
          )
        else
          Some(
            NotificationStatus(
              msgId = msgId,
              status = "pending",
              result = Some(NotificationResult.concatResults(workItems.map(_.item.result)))
            )
          )
      else None

  def sendNotification(request: SendNotificationRequest)(using HeaderCarrier): EitherT[Future, NotificationResult, SendNotificationResponse] =
    val msgId: UUID = UUID.randomUUID()

    handleRequest(msgId, request)
      .map(_ => SendNotificationResponse(msgId))

  private def createAndQueue(
    msgId     : UUID,
    request   : SendNotificationRequest,
    initResult: NotificationResult,
    toDo      : Seq[String]
  )(using HeaderCarrier
  ): EitherT[Future, NotificationResult, Unit] =
    EitherT.liftF {
      toDo.traverse_ { teamName =>
        for
          channelLookupResult <- channelLookupService.getExistingSlackChannel(teamName)
          (messages, result)  =  constructMessagesWithErrors(request, teamName, channelLookupResult, initResult)
          _                   <- messages.traverse_(msg => queueSlackMessage(msgId, msg, result, request.callbackChannel, Some(request.channelLookup)))
        yield ()
      }
    }

  private def handleRequest(msgId: UUID, request: SendNotificationRequest)(using HeaderCarrier): EitherT[Future, NotificationResult, Unit] =
    request.channelLookup match
      case ChannelLookup.GithubRepository(repoName) =>
        for
          repositoryDetails <- EitherT(channelLookupService.getExistingRepository(repoName))
          allTeams          <- EitherT.fromEither[Future](channelLookupService.getTeamsResponsibleForRepo(repoName, repositoryDetails))
          (excluded, toDo)  =  allTeams.partition(slackNotificationConfig.notRealTeams.contains)
          initResult        =  NotificationResult().addExclusion(excluded.map(Exclusion.notARealTeam): _*)
          _                 <- createAndQueue(msgId, request, initResult, toDo)
        yield ()
      case ChannelLookup.Service(serviceName) =>
        for
          repoName          <- EitherT.liftF(serviceConfigsConnector.repoNameForService(serviceName)).map(_.getOrElse(serviceName))
          repositoryDetails <- EitherT(channelLookupService.getExistingRepository(repoName))
          allTeams          <- EitherT.fromEither[Future](channelLookupService.getTeamsResponsibleForRepo(repoName, repositoryDetails))
          (excluded, toDo)  =  allTeams.partition(slackNotificationConfig.notRealTeams.contains)
          initResult        =  NotificationResult().addExclusion(excluded.map(Exclusion.notARealTeam): _*)
          _                 <- createAndQueue(msgId, request, initResult, toDo)
        yield ()
      case ChannelLookup.GithubTeam(team) =>
        EitherT.liftF[Future, NotificationResult, Unit] {
          for
            channelLookupResult <- channelLookupService.getExistingSlackChannel(team)
            (messages, result)  =  constructMessagesWithErrors(request, team, channelLookupResult, NotificationResult())
            res                 <- messages.traverse_(queueSlackMessage(msgId, _, result, request.callbackChannel,Some(request.channelLookup)))
          yield res
        }
      case ChannelLookup.SlackChannel(slackChannels) =>
        EitherT.liftF(
          slackChannels.toList.foldLeftM(Seq.empty[Unit]) { (acc, slackChannel) =>
            queueSlackMessage(msgId, createMessageFromRequest(request, slackChannel), NotificationResult(), request.callbackChannel, Some(request.channelLookup))
              .map(acc :+ _)
          }.map(_ => ())
        )
      case ChannelLookup.TeamsOfGithubUser(githubUsername) =>
        if slackNotificationConfig.notRealGithubUsers.contains(githubUsername) then
          EitherT.leftT[Future, Unit](NotificationResult().addExclusion(Exclusion.notARealGithubUser(githubUsername)))
        else
          EitherT.liftF(
            handleRequestForUser("github", githubUsername, userManagementService.getTeamsForGithubUser)(msgId, request)
          )
      case ChannelLookup.TeamsOfLdapUser(ldapUsername) =>
        EitherT.liftF(
          handleRequestForUser("ldap", ldapUsername, userManagementService.getTeamsForLdapUser)(msgId, request)
        )

  def constructMessagesWithErrors(
    request   : SendNotificationRequest,
    team      : String,
    lookupRes : Either[(Seq[AdminSlackId], FallbackChannel), TeamChannel],
    initResult: NotificationResult
  ): (Seq[SlackMessage], NotificationResult) =
    lookupRes match
      case Right(teamChannel) =>
        ( Seq(createMessageFromRequest(request, teamChannel.asString))
        , initResult
        )

      case Left((adminSlackIds, fallbackChannel)) if adminSlackIds.isEmpty =>
        val error = Error.unableToFindTeamSlackChannelInUMPandNoSlackAdmins(team)
        ( Seq(createMessageFromRequest(request, fallbackChannel.asString, Some(error.message)))
        , initResult.addError(error)
        )

      case Left((adminSlackIds, fallbackChannel)) =>
        val error = Error.unableToFindTeamSlackChannelInUMP(team, adminSlackIds.size)
        ( createMessageFromRequest(request, fallbackChannel.asString, Some(error.message)) +:
            adminSlackIds.map(id => createMessageFromRequest(request, id.asString, Some(Error.errorForAdminMissingTeamSlackChannel(team).message)))
        , initResult.addError(error)
        )

  private def handleRequestForUser(
    userType   : String,
    username   : String,
    teamsGetter: String => Future[List[TeamName]]
  )(
    msgId  : UUID,
    request: SendNotificationRequest
  )(using HeaderCarrier
  ): Future[Unit] =
    for
      allTeams         <- teamsGetter(username)
      (excluded, toDo) =  allTeams.map(_.asString).partition(slackNotificationConfig.notRealTeams.contains)
      initResult       =  NotificationResult().addExclusion(excluded.map(Exclusion.notARealTeam): _*)
      _                =  if toDo.nonEmpty then createAndQueue(msgId, request, initResult, toDo)
                          else
                            logger.info(s"Failed to find teams for usertype: $userType, username: $username. " +
                              s"Sending slack notification to Platops admin channel instead")

                            val fallbackChannel = slackConfig.noTeamFoundAlert.channel
                            val error = Error.teamsNotFoundForUsername(userType, username)

                            queueSlackMessage(msgId, createMessageFromRequest(request, fallbackChannel, Some(error.message)), initResult.addError(error), request.callbackChannel, Some(request.channelLookup))
    yield ()

  private def createMessageFromRequest(
    request     : SendNotificationRequest,
    slackChannel: String,
    errorMessage: Option[String] = None
  ): SlackMessage =
    errorMessage match
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

  private[services] def queueSlackMessage(
    msgId          : UUID,
    slackMessage   : SlackMessage,
    init           : NotificationResult,
    callbackChannel: Option[String],
    channelLookup  : Option[ChannelLookup]
  ): Future[Unit] =
    val queuedSlackMessage =
      QueuedSlackMessage(
        msgId           = msgId,
        callbackChannel = callbackChannel,
        channelLookup   = channelLookup,
        slackMessage    = slackMessage,
        result          = init
      )

    slackMessageQueue
      .add(queuedSlackMessage)
      .map: workItemId =>
        logger.info(s"Message with work item id: ${workItemId.toString} queued for request with msgId: ${msgId.toString}")

  def notifySender(queuedMsg: QueuedSlackMessage, error: Error): Future[Unit] =
    queuedMsg.callbackChannel.fold(Future.unit):
      callbackChannel =>
        val msg = SlackMessage(
          channel = callbackChannel,
          text = "Unable to send slack notification",
          blocks = Seq(SlackMessage.errorBlock(s"Slack API returned error for msgId: ${queuedMsg.msgId} when attempting to deliver the message to channel: `${queuedMsg.slackMessage.channel}`\n${queuedMsg.channelLookup.fold("")(cl => s"channelLookup: ```${Json.toJson(cl)}```\n")}code: `${error.code}`, msg: `${error.message}`")),
          attachments = Seq.empty,
          username = "slack-notifications",
          emoji = ":rotating_light:"
        )
        queueSlackMessage(
          msgId           = UUID.randomUUID(),
          slackMessage    = msg,
          init            = NotificationResult(),
          callbackChannel = None,
          channelLookup   = None
        )
  
  def processMessageFromQueue(workItem: WorkItem[QueuedSlackMessage])(using HeaderCarrier): Future[Unit] =
    if workItem.failureCount > 2 then
      logger.info(s"WorkItem: ${workItem.id} for msgId: ${workItem.item.msgId} has failed 3 times - marking as permanently failed")
      slackMessageQueue.markPermFailed(workItem.id).map(_ => ())
    else
      slackConnector
        .postChatMessage(workItem.item.slackMessage)
        .flatMap:
          case Right(_) =>
            for
              _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addSuccessfullySent(workItem.item.slackMessage.channel))
              _ <- slackMessageQueue.markSuccess(workItem.id)
            yield ()
          case Left(SlackError(code, errors)) if code == "channel_not_found" =>
            val error = Error.slackChannelNotFound(workItem.item.slackMessage.channel, workItem.item.channelLookup)
            logger.error(s"Unable to notify Slack channel ${workItem.item.slackMessage.channel} for msgId: ${workItem.item.msgId}, the following error occurred: ${error.message}")
            for
              _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addError(error))
              _ <- slackMessageQueue.markPermFailed(workItem.id)
              _ <- notifySender(workItem.item, error)
            yield ()
          case Left(SlackError(code, errors)) if code == "rate_limited" =>
            logger.warn(s"Received rate_limited response when attempting to notify Slack channel ${workItem.item.slackMessage.channel} for msgId: ${workItem.item.msgId}")
            Future.failed(RateLimitExceededException())
          case Left(SlackError(code, errors)) if code.startsWith("invalid_blocks") =>
            val error = Error.slackError(400, s"error: $code, details: ${errors.mkString(", ")}", workItem.item.slackMessage.channel, None)
            logger.error(s"Unable to notify Slack channel ${workItem.item.slackMessage.channel} for msgId: ${workItem.item.msgId}, the following error occurred: ${error.message}")
            for
              _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addError(error))
              _ <- slackMessageQueue.markPermFailed(workItem.id)
              _ <- notifySender(workItem.item, error)
            yield ()
          case Left(SlackError(code, errors)) =>
            val error = Error("slack_error", s"error: $code, details: ${errors.mkString(", ")}")
            logger.error(s"Unable to notify Slack channel ${workItem.item.slackMessage.channel} for msgId: ${workItem.item.msgId}, the following error occurred: ${error.message}")
            for
              _ <- slackMessageQueue.updateNotificationResult(workItem.id, workItem.item.result.addError(error))
              _ <- slackMessageQueue.markFailed(workItem.id)
              _ <- notifySender(workItem.item, error)
            yield ()
        .recoverWith:
          case NonFatal(ex) =>
            logger.warn(s"Unable to notify Slack channel ${workItem.item.slackMessage.channel} for msgId: ${workItem.item.msgId}", ex)
            slackMessageQueue.markFailed(workItem.id)
            Future.failed(ex)
