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

import cats.data.NonEmptyList
import org.bson.types.ObjectId
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.{DomainConfig, SlackConfig}
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, ServiceConfigsConnector, SlackConnector}
import uk.gov.hmrc.slacknotifications.controllers.v2.NotificationController.SendNotificationRequest
import uk.gov.hmrc.slacknotifications.model.ChannelLookup._
import uk.gov.hmrc.slacknotifications.model.{NotificationResult, QueuedSlackMessage, SlackMessage, Error}
import uk.gov.hmrc.slacknotifications.persistence.SlackMessageQueueRepository
import uk.gov.hmrc.slacknotifications.base.UnitSpec
import org.mockito.Mockito.{times, verify, when}
import org.mockito.ArgumentMatchers.{any, eq => eqTo}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NotificationServiceSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with ScalaCheckPropertyChecks:

  given HeaderCarrier = HeaderCarrier()

  "sendNotification" should:
    "work for all channel lookup types (happy path)" in new Fixtures:
      private val repoNameForService = "repo"
      private val teamName           = "team-name"
      private val repositoryDetails  = RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(repoNameForService)))
      when(mockChannelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(mockChannelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName)))

      private val teamChannel = TeamChannel("team-channel")
      private val usersTeams = List(TeamName("team-one"))

      private val channelLookups = List(
        GithubRepository("repo"),
        Service("service"),
        SlackChannel(NonEmptyList.of(teamChannel.asString)),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam("a-github-team")
      )

      when(mockUserManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(mockUserManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(mockChannelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(teamChannel)))
      when(mockSlackMessageQueue.add(any[QueuedSlackMessage]))
        .thenReturn(Future.successful(new ObjectId()))

      channelLookups.foreach: channelLookup =>
        val request =
          SendNotificationRequest(
            callbackChannel = Some("callback-channel"),
            displayName     = "a-display-name",
            emoji           = ":robot_face:",
            channelLookup   = channelLookup,
            text            = "a test message",
            blocks          = Seq.empty,
            attachments     = Seq.empty
          )

        val result = service.sendNotification(request).value.futureValue

        result should be a Symbol("right")

        channelLookup match
          case req: GithubRepository  => verify(mockChannelLookupService,  times(1)).getTeamsResponsibleForRepo(eqTo(req.repositoryName), eqTo(repositoryDetails))
          case _: Service             => verify(mockChannelLookupService,  times(2)).getTeamsResponsibleForRepo(eqTo(repoNameForService), eqTo(repositoryDetails))
          case req: TeamsOfGithubUser => verify(mockUserManagementService, times(1)).getTeamsForGithubUser(eqTo(req.githubUsername))(using any[HeaderCarrier])
          case req: TeamsOfLdapUser   => verify(mockUserManagementService, times(1)).getTeamsForLdapUser(eqTo(req.ldapUsername))(using any[HeaderCarrier])
          case req: GithubTeam        => verify(mockChannelLookupService,  times(1)).getExistingSlackChannel(eqTo(req.teamName))(using any[HeaderCarrier])
          case _                      =>

    "handle repo names being used in the service channel lookup" in new Fixtures:
      private val repoName          = "repo"
      private val teamName          = "team-name"
      private val repositoryDetails = RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      private val channelLookups = List(
        Service(repoName)
      )

      private val teamChannel = TeamChannel("team-channel")

      when(mockServiceConfigsConnector.repoNameForService(eqTo(repoName))(using any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(mockChannelLookupService.getExistingRepository(eqTo(repoName))(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(mockChannelLookupService.getTeamsResponsibleForRepo(eqTo(repoName), eqTo(repositoryDetails)))
        .thenReturn(Right(List(teamName)))

      when(mockChannelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(teamChannel)))
      when(mockSlackMessageQueue.add(any[QueuedSlackMessage]))
        .thenReturn(Future.successful(new ObjectId()))

      channelLookups.collect { case service: Service => service }.foreach: channelLookup =>
        val request =
          SendNotificationRequest(
            callbackChannel = Some("callback-channel"),
            displayName     = "a-display-name",
            emoji           = ":robot_face:",
            channelLookup   = channelLookup,
            text            = "a test message",
            blocks          = Seq.empty,
            attachments     = Seq.empty
          )

        val result = service.sendNotification(request).value.futureValue

        result should be a Symbol("right")

        verify(mockChannelLookupService, times(1)).getTeamsResponsibleForRepo(eqTo(channelLookup.serviceName), eqTo(repositoryDetails))

  "constructMessagesWithErrors" should:
    val team = "teamA"
    val request =
      SendNotificationRequest(
        callbackChannel = Some("callback-channel"),
        displayName     = "a-display-name",
        emoji           = ":robot_face:",
        channelLookup   = GithubTeam(team),
        text            = "a test message",
        blocks          = Seq.empty,
        attachments     = Seq.empty
      )
    val initResult = NotificationResult()
    val teamChannel = TeamChannel("https://hmrcdigital.slack.com/messages/teamA")
    val fallbackChannel = FallbackChannel("https://hmrcdigital.slack.com/messages/fallbackChannel")

    "handle Right case correctly" in new Fixtures:
      private val lookupRes = Right(teamChannel)
      private val result = service.constructMessagesWithErrors(request, team, lookupRes, initResult)
      result shouldBe (
        Seq(SlackMessage(
          channel     = teamChannel.asString,
          text        = request.text,
          blocks      = Seq.empty,
          attachments = Seq.empty,
          username    = request.displayName,
          emoji       = request.emoji
        )),
        initResult
      )

    "handle Left case without admins correctly" in new Fixtures:
      private val lookupRes = Left((Seq.empty[AdminSlackId], fallbackChannel))
      val error: Error = Error.unableToFindTeamSlackChannelInUMPandNoSlackAdmins(team)

      service.constructMessagesWithErrors(request, team, lookupRes, initResult) shouldBe ((
        Seq(SlackMessage(
          channel     = fallbackChannel.asString,
          text        = error.message,
          blocks      = Seq(SlackMessage.errorBlock(Error.unableToFindTeamSlackChannelInUMPandNoSlackAdmins(team).message), SlackMessage.divider) ++ request.blocks,
          attachments = Seq.empty,
          username    = request.displayName,
          emoji       = request.emoji
        )),
        initResult.addError(error)
      ))

    "handle Left case with admins correctly" in new Fixtures:
      private val lookupRes = Left((Seq(AdminSlackId("id_A"), AdminSlackId("id_B")), fallbackChannel))
      service.constructMessagesWithErrors(request, team, lookupRes, initResult) shouldBe (
        Seq(
          SlackMessage.sanitise( SlackMessage(
            channel     = fallbackChannel.asString,
            text        = Error.unableToFindTeamSlackChannelInUMP(team, lookupRes.value._1.size).message,
            blocks      = Seq(SlackMessage.errorBlock(Error.unableToFindTeamSlackChannelInUMP(team, lookupRes.value._1.size).message), SlackMessage.divider) ++ request.blocks,
            attachments = Seq.empty,
            username    = request.displayName,
            emoji       = request.emoji
          ), domainConfig),
          SlackMessage.sanitise( SlackMessage(
            channel     = "id_A",
            text        = Error.errorForAdminMissingTeamSlackChannel(team).message,
            blocks      = Seq(SlackMessage.errorBlock(Error.errorForAdminMissingTeamSlackChannel(team).message), SlackMessage.divider) ++ request.blocks,
            attachments = Seq.empty,
            username    = request.displayName,
            emoji       = request.emoji
          ), domainConfig),
          SlackMessage.sanitise( SlackMessage(
            channel     = "id_B",
            text        = Error.errorForAdminMissingTeamSlackChannel(team).message,
            blocks      = Seq(SlackMessage.errorBlock(Error.errorForAdminMissingTeamSlackChannel(team).message), SlackMessage.divider) ++ request.blocks,
            attachments = Seq.empty,
            username    = request.displayName,
            emoji       = request.emoji
          ), domainConfig)
        ),
        initResult.addError(Error.unableToFindTeamSlackChannelInUMP(team, lookupRes.value._1.size))
      )

  trait Fixtures:
    val mockUserManagementService  : UserManagementService       = mock[UserManagementService]
    val mockChannelLookupService   : ChannelLookupService        = mock[ChannelLookupService]
    val mockSlackMessageQueue      : SlackMessageQueueRepository = mock[SlackMessageQueueRepository]
    val mockSlackConnector         : SlackConnector              = mock[SlackConnector]
    val mockServiceConfigsConnector: ServiceConfigsConnector     = mock[ServiceConfigsConnector]

    val configuration: Configuration =
      Configuration(
        "slack.notification.enabled"       -> true
      , "alerts.slack.noTeamFound.channel" -> "test-channel"
      , "allowed.domains"                  ->  Seq("domain1", "domain2","tax.service.gov.uk")
      , "linkNotAllowListed"               -> "LINK NOT ALLOW LISTED"
      )

    lazy val service: NotificationService =
      NotificationService(
        slackNotificationConfig = new SlackNotificationConfig(configuration),
        slackConfig             = new SlackConfig(configuration),
        domainConfig            = new DomainConfig(configuration),
        userManagementService   = mockUserManagementService,
        channelLookupService    = mockChannelLookupService,
        slackMessageQueue       = mockSlackMessageQueue,
        slackConnector          = mockSlackConnector,
        serviceConfigsConnector = mockServiceConfigsConnector
      )

    val domainConfig: DomainConfig =
      DomainConfig(configuration)
