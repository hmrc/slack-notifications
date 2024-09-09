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
import org.scalacheck.Gen
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import uk.gov.hmrc.http.*
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.{DomainConfig, SlackConfig}
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, ServiceConfigsConnector, SlackConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.{GithubRepository, GithubTeam, Service, SlackChannel, TeamsOfGithubUser, TeamsOfLdapUser}
import uk.gov.hmrc.slacknotifications.model.*
import uk.gov.hmrc.slacknotifications.services.AuthService.ClientService
import uk.gov.hmrc.slacknotifications.base.UnitSpec
import org.mockito.Mockito.{times, verify, when}
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.scalatest.prop.TableFor2

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LegacyNotificationServiceSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with ScalaCheckPropertyChecks:

  given HeaderCarrier = HeaderCarrier()

  "Sending a Slack message" should:
    "succeed if slack accepted the notification" in new Fixtures:
      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      private val existingChannel = "existingChannel"
      val result: NotificationResult =
        service
          .sendSlackMessage(
            LegacySlackMessage(
              channel              = existingChannel,
              text                 = "text",
              username             = "someUser",
              icon_emoji           = Some(":snowman:"),
              attachments          = Nil,
              showAttachmentAuthor = true
            ),
            ClientService("", Password(""))
          )
          .futureValue

      result shouldBe NotificationResult().addSuccessfullySent(existingChannel)

    "return error if response was not 200" in new Fixtures:
      val invalidStatusCodes: Gen[Int] = Gen.choose(201, 599)
      forAll(invalidStatusCodes) { statusCode =>
        val errorMsg: String = "invalid_payload"
        when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(HttpResponse(statusCode, errorMsg)))

        val result: NotificationResult =
          service
            .sendSlackMessage(
              LegacySlackMessage(
                channel              = "nonexistentChannel",
                text                 = "text",
                username             = "someUser",
                icon_emoji           = Some(":snowman:"),
                attachments          = Nil,
                showAttachmentAuthor = true
              ),
              ClientService("", Password(""))
            )
            .futureValue

        result shouldBe NotificationResult().addError(Error.slackError(statusCode, errorMsg, "nonexistentChannel", None))
      }

    "return an error if an exception was thrown by the Slack connector" in new Fixtures:
      val errorMsg: String = "some exception message"

      val exceptionsAndErrors: TableFor2[UpstreamErrorResponse, Error] =
        Table(
          ("exception", "expected error"),
          (UpstreamErrorResponse(errorMsg, 400, 400), Error.slackError(400, errorMsg, "name-of-a-channel", None)),
          (UpstreamErrorResponse(errorMsg, 403, 403), Error.slackError(403, errorMsg, "name-of-a-channel", None)),
          (UpstreamErrorResponse(errorMsg, 500, 500), Error.slackError(500, errorMsg, "name-of-a-channel", None)),
          (UpstreamErrorResponse(errorMsg, 404, 404), Error.slackError(404, errorMsg, "name-of-a-channel", None))
        )

      forAll(exceptionsAndErrors) { (exception, expectedError) =>
        when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
          .thenReturn(Future.failed(exception))

        val result = service
          .sendSlackMessage(
            LegacySlackMessage(
              channel              = "name-of-a-channel",
              text                 = "text",
              username             = "someUser",
              icon_emoji           = Some(":snowman:"),
              attachments          = Nil,
              showAttachmentAuthor = true
            ),
            ClientService("", Password(""))
          )
          .futureValue

        result.errors.head shouldBe expectedError
      }

    "throw an exception if any other non-fatal exception was thrown" in new Fixtures:
      val errorMsg: String = "non-fatal exception"

      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.failed(RuntimeException(errorMsg)))

      val thrown: RuntimeException =
        the[RuntimeException] thrownBy {
          service
            .sendSlackMessage(
              LegacySlackMessage(
                channel              = "channel",
                text                 = "text",
                username             = "someUser",
                icon_emoji           = Some(":snowman:"),
                attachments          = Nil,
                showAttachmentAuthor = true
              ),
              ClientService("", Password("")))
            .futureValue
        }

      thrown.getCause.getMessage shouldBe errorMsg

  "Sending a notification" should:
    "work for all channel lookup types (happy path scenarios)" in new Fixtures:
      private val repoNameForService: String = "repo"
      private val teamName: String           = "team-name"
      private val repositoryDetails: RepositoryDetails =
        RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(repoNameForService)))
      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName)))

      val teamChannel: String        = "team-channel"
      val usersTeams: List[TeamName] = List(TeamName("team-one"))

      val channelLookups: List[ChannelLookup] =
        List(
          GithubRepository("repo"),
          Service("service"),
          SlackChannel(NonEmptyList.of(teamChannel)),
          TeamsOfGithubUser("a-github-handle"),
          TeamsOfLdapUser("a-ldap-user"),
          GithubTeam("a-github-team")
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(channelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(TeamChannel(teamChannel))))
      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach: channelLookup =>
        val notificationRequest: NotificationRequest =
          NotificationRequest(
            channelLookup  = channelLookup,
            messageDetails = MessageDetails(
                               text        = "some-text-to-post-to-slack",
                               attachments = Nil
                             )
          )

        val result: NotificationResult =
          service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )

        channelLookup match
          case req: GithubRepository  => verify(channelLookupService,  times(1)).getTeamsResponsibleForRepo(eqTo(req.repositoryName), eqTo(repositoryDetails))
          case _  : Service           => verify(channelLookupService,  times(2)).getTeamsResponsibleForRepo(eqTo(repoNameForService), eqTo(repositoryDetails))
          case req: TeamsOfGithubUser => verify(userManagementService, times(1)).getTeamsForGithubUser(eqTo(req.githubUsername))(using any[HeaderCarrier])
          case req: TeamsOfLdapUser   => verify(userManagementService, times(1)).getTeamsForLdapUser(eqTo(req.ldapUsername))(using any[HeaderCarrier])
          case req: GithubTeam        => verify(channelLookupService,  times(1)).getExistingSlackChannel(eqTo(req.teamName))(using any[HeaderCarrier])
          case _                      =>

    "Send the message to an admin channel if no slack channel is configured in UMP" in new Fixtures:
      val fallbackChannel: String = "slack-channel-missing"

      override val configuration: Configuration =
        Configuration(
          "slack.notification.enabled"         -> true,
          "alerts.slack.noTeamFound.channel"   -> fallbackChannel,
          "alerts.slack.noTeamFound.username"  -> "slack-notifications",
          "alerts.slack.noTeamFound.iconEmoji" -> "",
          "alerts.slack.noTeamFound.text"      -> "test {user}"
        )

      private val repoNameForService: String = "repo"
      private val teamName: String           = "team-name"
      private val repositoryDetails: RepositoryDetails =
        RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(repoNameForService)))
      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName)))

      val usersTeams: List[TeamName] = List(TeamName("team-name"))

      val channelLookups: List[ChannelLookup] =
        List(
          GithubRepository("repo"),
          Service("service"),
          TeamsOfGithubUser("a-github-handle"),
          TeamsOfLdapUser("a-ldap-user"),
          GithubTeam(teamName)
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(channelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Left((Seq.empty[AdminSlackId], FallbackChannel(fallbackChannel)))))
      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach: channelLookup =>
        val notificationRequest: NotificationRequest =
          NotificationRequest(
            channelLookup  = channelLookup,
            messageDetails = MessageDetails(
                               text        = "some-text-to-post-to-slack",
                               attachments = Nil
                             )
          )

        val result: NotificationResult =
          service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(fallbackChannel),
          errors             = Seq(Error.unableToFindTeamSlackChannelInUMPandNoSlackAdmins(teamName)),
          exclusions         = Nil
        )

    "Send the message to an admin channel if the team does not exist in UMP and has no slack admins" in new Fixtures:
      val fallbackChannel: String = "slack-channel-missing"

      override val configuration: Configuration =
        Configuration(
          "slack.notification.enabled"         -> true,
          "alerts.slack.noTeamFound.channel"   -> fallbackChannel,
          "alerts.slack.noTeamFound.username"  -> "slack-notifications",
          "alerts.slack.noTeamFound.iconEmoji" -> "",
          "alerts.slack.noTeamFound.text"      -> "test {user}"
        )

      private val teamName: String           = "team-name"
      private val repoNameForService: String = "repo"
      private val repositoryDetails: RepositoryDetails =
        RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(repoNameForService)))
      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName)))

      val usersTeams: List[TeamName] = List(TeamName("team-name"))

      val channelLookups: List[ChannelLookup] =
        List(
          GithubRepository("repo"),
          Service("service"),
          TeamsOfGithubUser("a-github-handle"),
          TeamsOfLdapUser("a-ldap-user"),
          GithubTeam(teamName)
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(channelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Left((Seq.empty[AdminSlackId], FallbackChannel(fallbackChannel)))))
      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach: channelLookup =>
        val notificationRequest: NotificationRequest =
          NotificationRequest(
            channelLookup  = channelLookup,
            messageDetails = MessageDetails(
                               text        = "some-text-to-post-to-slack",
                               attachments = Nil
                             )
          )

        val result: NotificationResult =
          service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(fallbackChannel),
          errors             = Seq(Error.unableToFindTeamSlackChannelInUMPandNoSlackAdmins(teamName)),
          exclusions         = Nil
        )

    "Send the message to an admin channel and team admins if the team does not exist in UMP" in new Fixtures:
      val fallbackChannel: String          = "slack-channel-missing"
      val adminSlackIds: Seq[AdminSlackId] = Seq(AdminSlackId("id_A"), AdminSlackId("id_B"))

      override val configuration: Configuration =
        Configuration(
          "slack.notification.enabled"         -> true,
          "alerts.slack.noTeamFound.channel"   -> fallbackChannel,
          "alerts.slack.noTeamFound.username"  -> "slack-notifications",
          "alerts.slack.noTeamFound.iconEmoji" -> "",
          "alerts.slack.noTeamFound.text"      -> "test {user}"
        )

      private val teamName: String           = "team-name"
      private val repoNameForService: String = "repo"
      private val repositoryDetails: RepositoryDetails =
        RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(repoNameForService)))
      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName)))

      val usersTeams: List[TeamName] = List(TeamName("team-name"))

      val channelLookups: List[ChannelLookup] =
        List(
          GithubRepository("repo"),
          Service("service"),
          TeamsOfGithubUser("a-github-handle"),
          TeamsOfLdapUser("a-ldap-user"),
          GithubTeam(teamName)
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(channelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Left((adminSlackIds, FallbackChannel(fallbackChannel)))))
      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach: channelLookup =>
        val notificationRequest: NotificationRequest =
          NotificationRequest(
            channelLookup  = channelLookup,
            messageDetails = MessageDetails(
                               text        = "some-text-to-post-to-slack",
                               attachments = Nil
                             )
          )

        val result: NotificationResult =
          service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List("id_A", "id_B", fallbackChannel),
          errors             = Seq(Error.errorForAdminMissingTeamSlackChannel(teamName), Error.unableToFindTeamSlackChannelInUMP(teamName, adminSlackIds.size)),
          exclusions         = Nil
        )

    "report if no teams are found for a github user" in new Fixtures:
      val githubUsername: String = "a-github-username"
      val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser(githubUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(List.empty))

      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(Error.teamsNotFoundForUsername("github", githubUsername)),
        exclusions         = Nil
      )

    "report if no teams are found for a ldap user" in new Fixtures:
      val ldapUsername: String = "a-ldap-username"
      val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfLdapUser(ldapUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(List.empty))

      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(Error.teamsNotFoundForUsername("ldap", ldapUsername)),
        exclusions         = Nil
      )

    "handle repo names being used in the service channel lookup" in new Fixtures:
      private val repoName: String = "repo"
      private val teamName: String = "team-name"
      private val repositoryDetails: RepositoryDetails =
        RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(eqTo(repoName))(using any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(channelLookupService.getExistingRepository(eqTo(repoName))(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(channelLookupService.getTeamsResponsibleForRepo(eqTo(repoName), eqTo(repositoryDetails)))
        .thenReturn(Right(List(teamName)))

      val channelLookups: List[Service] =
        List(
          Service(repoName)
        )

      private val teamChannel: TeamChannel = TeamChannel("team-channel")

      when(channelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(teamChannel)))
      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.collect { case service: Service => service }.foreach: channelLookup =>
        val notificationRequest: NotificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              attachments = Nil
            )
          )

        val result: NotificationResult =
          service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel.asString),
          errors             = Nil,
          exclusions         = Nil
        )

        verify(channelLookupService, times(1)).getTeamsResponsibleForRepo(eqTo(channelLookup.serviceName), eqTo(repositoryDetails))

  "Disabled notifications" should:
    "not send alerts for all channel lookup types" in new Fixtures:
      private val teamName: String = "team-name"
      override val configuration: Configuration = Configuration("slack.notification.enabled" -> false)
      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName)))

      val teamChannel: TeamChannel   = TeamChannel("team-channel")
      val usersTeams: List[TeamName] = List(TeamName("team-name"))

      val channelLookups: List[ChannelLookup] =
        List(
          GithubRepository("repo"),
          SlackChannel(NonEmptyList.of(teamChannel.asString)),
          TeamsOfGithubUser("a-github-handle"),
          TeamsOfLdapUser("a-ldap-user"),
          GithubTeam("a-github-team")
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(userManagementService.getTeamsForLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(channelLookupService.getExistingSlackChannel(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(teamChannel)))

      channelLookups.foreach: channelLookup =>
        val notificationRequest: NotificationRequest =
          NotificationRequest(
            channelLookup  = channelLookup,
            messageDetails = MessageDetails(
                               text        = "some-text-to-post-to-slack",
                               attachments = Nil
                             )
          )

        val result: NotificationResult =
          service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue
        val space = " " //to stop ide from removing end spaces

        val slackMessageStr = s"""
                               |   Channel: team-channel
                               |   Message: some-text-to-post-to-slack
                               |   Username:$space
                               |   Emoji:$space
                               |""".stripMargin

        result shouldBe NotificationResult(
          successfullySentTo = List(),
          errors = List(),
          exclusions = List(Exclusion.notificationDisabled(slackMessageStr)))

  "Sending a request for teams of a github user" should:
    "not include excluded teams based on configuration" in new Fixtures:
      val teamName1: String = "team-to-be-excluded-1"
      val teamName2: String = "team-to-be-excluded-2"
      override val configuration: Configuration =
        Configuration(
          "exclusions.notRealTeams"            -> s"$teamName1, $teamName2",
          "slack.notification.enabled"         -> true,
          "alerts.slack.noTeamFound.channel"   -> "test-channel",
          "alerts.slack.noTeamFound.username"  -> "slack-notifications",
          "alerts.slack.noTeamFound.iconEmoji" -> "",
          "alerts.slack.noTeamFound.text"      -> "test {user}"
        )
      val githubUsername: String     = "a-github-username"
      val usersTeams: List[TeamName] = List(TeamName(teamName1), TeamName(teamName2))

      val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser(githubUsername),
          messageDetails = exampleMessageDetails
        )

      when(userManagementService.getTeamsForGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))

      when(slackConnector.sendMessage(any[LegacySlackMessage])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(Error.teamsNotFoundForUsername("github", githubUsername)),
        exclusions         = List(Exclusion.notARealTeam(teamName1), Exclusion.notARealTeam(teamName2))
      )
      
    "not include ignored github user names, e.g. LDS dummy commiter for admin endpoints" in new Fixtures:
      val ignored1: String = "n/1"
      val ignored2: String = "ignored2"
      override val configuration: Configuration =
        Configuration(
          "exclusions.notRealGithubUsers"      -> s"$ignored1, $ignored2",
          "slack.notification.enabled"         -> true
        )

      val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = TeamsOfGithubUser(ignored1),
          messageDetails = exampleMessageDetails
        )

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = Nil,
        exclusions         = List(Exclusion.notARealGithubUser(ignored1))
      )

  "Sending a request with github repository lookup" should:
    "don't send notifications for a predefined set of ignored teams" in new Fixtures:
      private val teamName1: String = "team-to-be-excluded-1"
      private val teamName2: String = "team-to-be-excluded-2"
      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(RepositoryDetails(teamNames = List(teamName1, teamName2), owningTeams = Nil))))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Right(List(teamName1, teamName2)))

      override val configuration: Configuration =
        Configuration(
          "exclusions.notRealTeams"    -> s"$teamName1, $teamName2",
          "slack.notification.enabled" -> true
        )

      val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository("repo"),
          messageDetails = exampleMessageDetails
        )

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = Nil,
        exclusions         = List(Exclusion.notARealTeam(teamName1), Exclusion.notARealTeam(teamName2))
      )

    "report if a repository does not exist" in new Fixtures:
      private val nonexistentRepoName: String = "nonexistent-repo"

      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Left(NotificationResult().addError(Error.repositoryNotFound(nonexistentRepoName)))))

      private val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository(nonexistentRepoName),
          messageDetails = exampleMessageDetails
        )

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(Error.repositoryNotFound(nonexistentRepoName)),
        exclusions         = Nil
      )

    "report if no team is assigned to a repository" in new Fixtures:
      val repoName: String = "repo-name"

      when(channelLookupService.getExistingRepository(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(RepositoryDetails(teamNames = List(), owningTeams = Nil))))
      when(channelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Left(NotificationResult().addError(Error.teamsNotFoundForRepository(repoName))))

      private val notificationRequest: NotificationRequest =
        NotificationRequest(
          channelLookup  = GithubRepository(repoName),
          messageDetails = exampleMessageDetails
        )

      val result: NotificationResult =
        service.sendNotification(notificationRequest, ClientService("", Password(""))).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(Error.teamsNotFoundForRepository(repoName)),
        exclusions         = Nil
      )

  "Sanitising a slack message" should:
    "strip out the emoji and use displayName from config to determine author name" in new Fixtures:
      val result: LegacySlackMessage =
        service.populateNameAndIconInMessage(
          LegacySlackMessage(
            channel    = "",
            text       = "",
            username   = "",
            icon_emoji = Some(":eyes:"),
            attachments = Seq(
              Attachment(
                None,
                None,
                None,
                author_name = Some("username"),
                None,
                Some(":monkey:"),
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None)),
            showAttachmentAuthor = true
          ),
          ClientService("leak-detection", Password(""))
        )

      result.username                     should be("leak-detector")
      result.icon_emoji                   should be(None)
      result.attachments.head.author_name should be(Some("leak-detector"))
      result.attachments.head.author_icon should be(None)

    "strip out the emoji and use name from config to determine author name when no displayName is configured" in new Fixtures:
      val result: LegacySlackMessage =
        service.populateNameAndIconInMessage(
          LegacySlackMessage(
            channel    = "",
            text       = "",
            username   = "",
            icon_emoji = Some(":eyes:"),
            attachments = Seq(
              Attachment(
                None,
                None,
                None,
                author_name = Some("username"),
                None,
                Some(":monkey:"),
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None)),
            showAttachmentAuthor = true
          ),
          ClientService("another-service", Password(""))
        )

      result.username                     should be("another-service")
      result.icon_emoji                   should be(None)
      result.attachments.head.author_name should be(Some("another-service"))
      result.attachments.head.author_icon should be(None)

    "Don't set author name when showAttachmentAuthor=false" in new Fixtures:
      val result: LegacySlackMessage =
          service.populateNameAndIconInMessage(
          LegacySlackMessage(
            channel = "",
            text = "",
            username = "",
            icon_emoji = Some(":eyes:"),
            attachments = Seq(
              Attachment(
                None,
                None,
                None,
                author_name = Some("username"),
                None,
                Some(":monkey:"),
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None,
                None)),
            showAttachmentAuthor = false
          ),
          ClientService("another-service", Password(""))
        )
      result.attachments.head.author_name should be(None)

  trait Fixtures:
    val slackConnector: SlackConnector                       = mock[SlackConnector]//(withSettings.lenient)
    val userManagementService: UserManagementService         = mock[UserManagementService]
    val channelLookupService: ChannelLookupService           = mock[ChannelLookupService]
    val mockServiceConfigsConnector: ServiceConfigsConnector = mock[ServiceConfigsConnector]

    val configuration: Configuration =
      Configuration(
        "auth.authorizedServices.0.name"        -> "leak-detection"
      , "auth.authorizedServices.0.password"    -> "foo"
      , "auth.authorizedServices.0.displayName" -> "leak-detector"
      , "auth.authorizedServices.1.name"        -> "another-service"
      , "auth.authorizedServices.1.password"    -> "foo"
      , "slack.notification.enabled"            -> true
      , "alerts.slack.noTeamFound.channel"      -> "test-channel"
      , "alerts.slack.noTeamFound.username"     -> "slack-notifications"
      , "alerts.slack.noTeamFound.iconEmoji"    -> ""
      , "alerts.slack.noTeamFound.text"         -> "test {user}"
      )

    val exampleMessageDetails: MessageDetails =
      MessageDetails(
        text        = "some-text-to-post-to-slack",
        attachments = Nil
      )

    lazy val service =
      LegacyNotificationService(
        SlackNotificationConfig(configuration),
        slackConnector,
        SlackConfig(configuration),
        DomainConfig(Configuration(
          "allowed.domains"    ->  Seq("domain1", "domain2")
        , "linkNotAllowListed" -> "LINK NOT ALLOW LISTED"
        )),
        userManagementService,
        channelLookupService,
        mockServiceConfigsConnector
      )
