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
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.slacknotifications.SlackNotificationConfig
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.model.ChannelLookup._
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamDetails, TeamName}
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.controllers.v2.NotificationController.SendNotificationRequest
import uk.gov.hmrc.slacknotifications.model.{NotificationResult, SlackMessage}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NotificationServiceSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with ScalaCheckPropertyChecks {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "sendNotification" should {
    "work for all channel lookup types (happy path)" in new Fixtures {
      private val teamName = "team-name"
      when(mockTeamsAndReposConnector.getRepositoryDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(RepositoryDetails(teamNames = List(teamName), owningTeams = Nil))))

      val teamChannel = "team-channel"
      val usersTeams = List(TeamName("team-one"))
      val teamDetails = TeamDetails(slack = Some(s"https://foo.slack.com/$teamChannel"), teamName = "team-one", slackNotification = None)

      val channelLookups = List(
        GithubRepository("repo"),
        SlackChannel(NonEmptyList.of(teamChannel)),
        TeamsOfGithubUser("a-github-handle"),
        TeamsOfLdapUser("a-ldap-user"),
        GithubTeam("a-github-team")
      )

      when(mockUserManagementService.getTeamsForGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(mockUserManagementService.getTeamsForLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(usersTeams))
      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(teamDetails)))
      when(mockSlackConnector.chatPostMessage(any[SlackMessage])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(200, "")))

      channelLookups.foreach { channelLookup =>
        val request =
          SendNotificationRequest(
            displayName   = "a-display-name",
            emoji         = ":robot_face:",
            channelLookup = channelLookup,
            text          = "a test message",
            blocks        = Seq.empty
          )

        val result = service.sendNotification(request).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )

        channelLookup match {
          case req: TeamsOfGithubUser => verify(mockUserManagementService, times(1)).getTeamsForGithubUser(eqTo(req.githubUsername))(any)
          case req: TeamsOfLdapUser   => verify(mockUserManagementService, times(1)).getTeamsForLdapUser(eqTo(req.ldapUsername))(any)
          case req: GithubTeam        => verify(mockUserManagementConnector, times(1)).getTeamSlackDetails(eqTo(req.teamName))(any)
          case _                      =>
        }
      }
    }
  }

  trait Fixtures {
    val mockSlackConnector          = mock[SlackConnector] //(withSettings.lenient)
    val mockTeamsAndReposConnector  = mock[TeamsAndRepositoriesConnector] //(withSettings.lenient)
    val mockUserManagementConnector = mock[UserManagementConnector] //(withSettings.lenient)
    val mockUserManagementService   = mock[UserManagementService]

    val configuration =
      Configuration(
        "slack.notification.enabled"       -> true,
        "alerts.slack.noTeamFound.channel" -> "test-channel",
      )

    lazy val service = new NotificationService(
      slackNotificationConfig = new SlackNotificationConfig(configuration),
      slackConfig             = new SlackConfig(configuration),
      slackConnector          = mockSlackConnector,
      teamsAndReposConnector  = mockTeamsAndReposConnector,
      userManagementConnector = mockUserManagementConnector,
      userManagementService   = mockUserManagementService
    )
  }

}
