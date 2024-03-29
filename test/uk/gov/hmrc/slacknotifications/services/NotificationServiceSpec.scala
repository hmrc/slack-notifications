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
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.model.ChannelLookup._
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, ServiceConfigsConnector, SlackConnector}
import uk.gov.hmrc.slacknotifications.controllers.v2.NotificationController.SendNotificationRequest
import uk.gov.hmrc.slacknotifications.model.QueuedSlackMessage
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.slacknotifications.config.DomainConfig
import uk.gov.hmrc.slacknotifications.persistence.SlackMessageQueueRepository

class NotificationServiceSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with ScalaCheckPropertyChecks {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "sendNotification" should {
    "work for all channel lookup types (happy path)" in new Fixtures {
      private val repoNameForService = "repo"
      private val teamName           = "team-name"
      private val repositoryDetails  = RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(repoNameForService)))
      when(mockChannelLookupService.getExistingRepository(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(mockChannelLookupService.getTeamsResponsibleForRepo(any[String], any[RepositoryDetails]))
        .thenReturn(Future.successful(Right(List(teamName))))

      private val teamChannel = "team-channel"
      private val usersTeams = List(TeamName("team-one"))

      private val channelLookups = List(
        GithubRepository("repo"),
        Service("service"),
        SlackChannel(NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam("a-github-team")
      )

      when(mockUserManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(mockUserManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(mockChannelLookupService.getExistingSlackChannel(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(teamChannel)))
      when(mockSlackMessageQueue.add(any[QueuedSlackMessage]))
        .thenReturn(Future.successful(new ObjectId()))

      channelLookups.foreach { channelLookup =>
        val request =
          SendNotificationRequest(
            displayName   = "a-display-name",
            emoji         = ":robot_face:",
            channelLookup = channelLookup,
            text          = "a test message",
            blocks        = Seq.empty,
            attachments   = Seq.empty
          )

        val result = service.sendNotification(request).value.futureValue

        result should be a Symbol("right")

        channelLookup match {
          case req: GithubRepository  => verify(mockChannelLookupService,  times(1)).getTeamsResponsibleForRepo(eqTo(req.repositoryName), eqTo(repositoryDetails))
          case _: Service             => verify(mockChannelLookupService,  times(2)).getTeamsResponsibleForRepo(eqTo(repoNameForService), eqTo(repositoryDetails))
          case req: TeamsOfGithubUser => verify(mockUserManagementService, times(1)).getTeamsForGithubUser(eqTo(req.githubUsername))(any)
          case req: TeamsOfLdapUser   => verify(mockUserManagementService, times(1)).getTeamsForLdapUser(eqTo(req.ldapUsername))(any)
          case req: GithubTeam        => verify(mockChannelLookupService,  times(1)).getExistingSlackChannel(eqTo(req.teamName))(any)
          case _                      =>
        }
      }
    }

    "handle repo names being used in the service channel lookup" in new Fixtures {
      private val repoName          = "repo"
      private val teamName          = "team-name"
      private val repositoryDetails = RepositoryDetails(teamNames = List(teamName), owningTeams = Nil)

      when(mockServiceConfigsConnector.repoNameForService(eqTo(repoName))(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(mockChannelLookupService.getExistingRepository(eqTo(repoName))(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(repositoryDetails)))
      when(mockChannelLookupService.getTeamsResponsibleForRepo(eqTo(repoName), eqTo(repositoryDetails)))
        .thenReturn(Future.successful(Right(List(teamName))))

      private val channelLookups = List(
        Service(repoName)
      )

      private val teamChannel = "team-channel"

      when(mockChannelLookupService.getExistingSlackChannel(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(teamChannel)))
      when(mockSlackMessageQueue.add(any[QueuedSlackMessage]))
        .thenReturn(Future.successful(new ObjectId()))

      channelLookups.foreach { channelLookup =>
        val request =
          SendNotificationRequest(
            displayName   = "a-display-name",
            emoji         = ":robot_face:",
            channelLookup = channelLookup,
            text          = "a test message",
            blocks        = Seq.empty,
            attachments   = Seq.empty
          )

        val result = service.sendNotification(request).value.futureValue

        result should be a Symbol("right")

        channelLookup match {
          case req: Service => verify(mockChannelLookupService, times(1)).getTeamsResponsibleForRepo(eqTo(req.serviceName), eqTo(repositoryDetails))
          case _            =>
        }
      }
    }
  }

  trait Fixtures {
    val mockUserManagementService  : UserManagementService       = mock[UserManagementService]
    val mockChannelLookupService   : ChannelLookupService        = mock[ChannelLookupService]
    val mockSlackMessageQueue      : SlackMessageQueueRepository = mock[SlackMessageQueueRepository]
    val mockSlackConnector         : SlackConnector              = mock[SlackConnector]
    val mockServiceConfigsConnector: ServiceConfigsConnector     = mock[ServiceConfigsConnector]

    val configuration: Configuration =
      Configuration(
        "slack.notification.enabled"       -> true
      , "alerts.slack.noTeamFound.channel" -> "test-channel"
      , "allowed.domains"                  ->  Seq("domain1", "domain2")
      , "linkNotAllowListed"               -> "LINK NOT ALLOW LISTED"
      )

    lazy val service = new NotificationService(
      slackNotificationConfig = new SlackNotificationConfig(configuration),
      slackConfig             = new SlackConfig(configuration),
      domainConfig            = new DomainConfig(configuration),
      userManagementService   = mockUserManagementService,
      channelLookupService    = mockChannelLookupService,
      slackMessageQueue       = mockSlackMessageQueue,
      slackConnector          = mockSlackConnector,
      serviceConfigsConnector = mockServiceConfigsConnector
    )
  }
}
