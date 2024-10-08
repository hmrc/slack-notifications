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
import uk.gov.hmrc.slacknotifications.connectors.{TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.base.UnitSpec
import org.mockito.Mockito.when
import org.mockito.ArgumentMatchers.any

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ChannelLookupServiceSpec
  extends UnitSpec with ScalaFutures:
  given HeaderCarrier = HeaderCarrier()

  "Extracting team slack channel" should:
    "work if slack field exists and contains team name at the end" in new Fixtures:
      val teamChannelName: String  = "teamChannel"
      val slackLink: String        = "foo/" + teamChannelName
      val teamDetails: TeamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(TeamChannel(teamChannelName))

    "return the slackNotification channel when present" in new Fixtures:
      val teamChannelName: String  = "teamChannel"
      val slackLink: String        = "foo/" + teamChannelName
      val teamDetails: TeamDetails = TeamDetails(
                                       slack             = Some(slackLink),
                                       slackNotification = Some(s"foo/$teamChannelName-notification"),
                                       teamName = "n/a"
                                     )

      service.extractSlackChannel(teamDetails) shouldBe Some(TeamChannel(s"$teamChannelName-notification"))

    "return None if slack field exists but there is no slack channel in it" in new Fixtures:
      val slackLink   = "link-without-team/"
      val teamDetails: TeamDetails =
        TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe None

    "return None if slack field doesn't exist" in new Fixtures:
      val teamDetails: TeamDetails =
        TeamDetails(slack = None, slackNotification = None, teamName = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None

    "return None if slack field does not contain a forward slash" in new Fixtures:
      val teamDetails: TeamDetails =
        TeamDetails(slack = Some("not a url"), slackNotification = None, teamName = "n/a")
      service.extractSlackChannel(teamDetails) shouldBe None

  "Getting an existing channel" should:
    "return TeamChannel if it exists" in new Fixtures:
      val teamName: String = "teamA"
      val teamDetails: TeamDetails =
        TeamDetails(teamName, None, Some("https://hmrcdigital.slack.com/messages/teamA"))

      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(teamDetails)))

      service.getExistingSlackChannel(teamName).futureValue shouldBe Right(TeamChannel("teamA"))

    "return only FallbackChannel channel if team channel and admins with slack do not exist" in new Fixtures:
      val teamName: String = "teamA"
      val fallbackChannel: FallbackChannel =
        FallbackChannel("https://hmrcdigital.slack.com/messages/fallbackChannel")

      when(mockSlackConfig.noTeamFoundAlert)
        .thenReturn(MessageConfig(fallbackChannel.asString, "", "", ""))
      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(mockUserManagementConnector.getTeamUsers(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq.empty))

      service.getExistingSlackChannel(teamName).futureValue shouldBe Left((Seq.empty, fallbackChannel))

    "return AdminSlackID and FallbackChannel if team channel does not exist but admins with slack do" in new Fixtures:
      val teamName: String = "teamA"
      val fallbackChannel: FallbackChannel =
        FallbackChannel("https://hmrcdigital.slack.com/messages/fallbackChannel")

      when(mockSlackConfig.noTeamFoundAlert)
        .thenReturn(MessageConfig(fallbackChannel.asString, "", "", ""))
      when(mockUserManagementConnector.getTeamSlackDetails(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(None))
      when(mockUserManagementConnector.getTeamUsers(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(
          Seq(
            UserManagementConnector.User(ldapUsername = "A", slackId = Some("id_A"), githubUsername = None, role = "user"      , teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "B", slackId = Some("id_B"), githubUsername = None, role = "team_admin", teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "C", slackId = Some("id_C"), githubUsername = None, role = "team_admin", teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "D", slackId = None        , githubUsername = None, role = "user"      , teamNames = List.empty),
            UserManagementConnector.User(ldapUsername = "E", slackId = None        , githubUsername = None, role = "team_admin", teamNames = List.empty)
          )
        ))

      service.getExistingSlackChannel(teamName).futureValue shouldBe
        Left((Seq(AdminSlackId("id_B"), AdminSlackId("id_C")), fallbackChannel))

  trait Fixtures:

    val mockSlackConfig: SlackConfig                         = mock[SlackConfig]
    val mockTeamsAndReposConn: TeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val mockUserManagementConnector: UserManagementConnector = mock[UserManagementConnector]

    lazy val service: ChannelLookupService =
      ChannelLookupService(
        mockSlackConfig,
        mockTeamsAndReposConn,
        mockUserManagementConnector
      )
