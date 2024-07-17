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

import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.config.{MessageConfig, SlackConfig}
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ChannelLookupServiceSpec
  extends UnitSpec with ScalaFutures {
  implicit val hc: HeaderCarrier = HeaderCarrier()


  "Getting teams responsible for repo" should {
    "prioritize owningTeams" in new Fixtures {
      val repoDetails: RepositoryDetails =
        RepositoryDetails(
          owningTeams = List("team1"),
          teamNames = List("team1", "team2")
        )

      service.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1")
    }
    "return contributing teams if no explicit owning teams are specified" in new Fixtures {
      val repoDetails: RepositoryDetails =
        RepositoryDetails(
          owningTeams = Nil,
          teamNames = List("team1", "team2")
        )

      service.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1", "team2")
    }
  }

  "Extracting team slack channel" should {
    "work if slack field exists and contains team name at the end" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink = "foo/" + teamChannelName
      val teamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(TeamChannel(teamChannelName))
    }

    "return the slackNotification channel when present" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink = "foo/" + teamChannelName
      val teamDetails = TeamDetails(
        slack = Some(slackLink),
        slackNotification = Some(s"foo/$teamChannelName-notification"),
        teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(TeamChannel(s"$teamChannelName-notification"))
    }

    "return None if slack field exists but there is no slack channel in it" in new Fixtures {
      val slackLink = "link-without-team/"
      val teamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field doesn't exist" in new Fixtures {
      val teamDetails = TeamDetails(slack = None, slackNotification = None, teamName = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field does not contain a forward slash" in new Fixtures {
      val teamDetails = TeamDetails(slack = Some("not a url"), slackNotification = None, teamName = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None
    }
  }

  "Getting an existing channel" should {
    "return TeamChannel if it exists" in new Fixtures {
      val teamName = "teamA"
      val teamDetails = TeamDetails(teamName, None, Some("https://hmrcdigital.slack.com/messages/teamA"))

      when(mockSlackConfig.noTeamFoundAlert)
        .thenReturn(MessageConfig("https://hmrcdigital.slack.com/messages/fallbackChannel", "", "", ""))
      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(teamDetails)))

      service.getExistingSlackChannel(teamName).value.futureValue shouldBe Right(TeamChannel("teamA"))
    }

    "return only FallbackChannel channel if team channel and admins with slack do not exist" in new Fixtures {
      val teamName = "teamA"
      val fallbackChannel = FallbackChannel("https://hmrcdigital.slack.com/messages/fallbackChannel")

      when(mockSlackConfig.noTeamFoundAlert)
        .thenReturn(MessageConfig(fallbackChannel.asString, "", "", ""))
      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(mockUserManagementConnector.getTeamUsers(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq.empty))

      service.getExistingSlackChannel(teamName).value.futureValue shouldBe Left((Seq.empty, fallbackChannel))
    }

    "return AdminSlackID and FallbackChannel if team channel does not exist but admins with slack do" in new Fixtures {
      val teamName = "teamA"
      val fallbackChannel = FallbackChannel("https://hmrcdigital.slack.com/messages/fallbackChannel")

      when(mockSlackConfig.noTeamFoundAlert)
        .thenReturn(MessageConfig(fallbackChannel.asString, "", "", ""))
      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(mockUserManagementConnector.getTeamUsers(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(
          Seq(
            UserManagementConnector.User(ldapUsername = "A", slackID = Some("id_A"), githubUsername = None, role = "user", teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "B", slackID = Some("id_B"), githubUsername = None, role = "team_admin", teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "C", slackID = Some("id_C"), githubUsername = None, role = "team_admin", teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "D", slackID = None, githubUsername = None, role = "user", teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "E", slackID = None, githubUsername = None, role = "team_admin", teamNames = List.empty)
          )
        ))

      service.getExistingSlackChannel(teamName).value.futureValue shouldBe
        Left((Seq(AdminSlackID("id_B"), AdminSlackID("id_C")), fallbackChannel))
    }

  }

  trait Fixtures {

    val mockSlackConfig: SlackConfig                         = mock[SlackConfig]
    val mockTeamsAndReposConn: TeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val mockUserManagementConnector: UserManagementConnector = mock[UserManagementConnector]

    lazy val service: ChannelLookupService =
      new ChannelLookupService(
        mockSlackConfig,
        mockTeamsAndReposConn,
        mockUserManagementConnector
      )
  }

}
