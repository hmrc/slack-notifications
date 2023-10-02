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

import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext

class ChannelLookupFunctionsSpec
  extends UnitSpec
{

  "Getting teams responsible for repo" should {
    "prioritize owningTeams" in new Fixtures {
      val repoDetails: RepositoryDetails =
        RepositoryDetails(
          owningTeams = List("team1"),
          teamNames = List("team1", "team2")
        )

      clf.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1")
    }
    "return contributing teams if no explicit owning teams are specified" in new Fixtures {
      val repoDetails: RepositoryDetails =
        RepositoryDetails(
          owningTeams = Nil,
          teamNames = List("team1", "team2")
        )

      clf.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1", "team2")
    }
  }

  "Extracting team slack channel" should {
    "work if slack field exists and contains team name at the end" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink = "foo/" + teamChannelName
      val teamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      clf.extractSlackChannel(teamDetails) shouldBe Some(teamChannelName)
    }

    "return the slackNotification channel when present" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink = "foo/" + teamChannelName
      val teamDetails = TeamDetails(
        slack = Some(slackLink),
        slackNotification = Some(s"foo/$teamChannelName-notification"),
        teamName = "n/a")

      clf.extractSlackChannel(teamDetails) shouldBe Some(s"$teamChannelName-notification")
    }

    "return None if slack field exists but there is no slack channel in it" in new Fixtures {
      val slackLink = "link-without-team/"
      val teamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      clf.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field doesn't exist" in new Fixtures {
      val teamDetails = TeamDetails(slack = None, slackNotification = None, teamName = "n/a")
      clf.extractSlackChannel(teamDetails) shouldBe None
    }

    "return None if slack field does not contain a forward slash" in new Fixtures {
      val teamDetails = TeamDetails(slack = Some("not a url"), slackNotification = None, teamName = "n/a")
      clf.extractSlackChannel(teamDetails) shouldBe None
    }
  }

  trait Fixtures {

    val mockSlackConfig: SlackConfig                         = mock[SlackConfig]
    val mockTeamsAndReposConn: TeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val mockUserManagementConnector: UserManagementConnector = mock[UserManagementConnector]

    lazy val clf: ChannelLookupFunctions = new ChannelLookupFunctions {
      override implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
      override val slackConfig: SlackConfig = mockSlackConfig
      override val teamsAndReposConnector: TeamsAndRepositoriesConnector = mockTeamsAndReposConn
      override val userManagementConnector: UserManagementConnector = mockUserManagementConnector
    }
  }

}
