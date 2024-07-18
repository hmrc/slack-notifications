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

package uk.gov.hmrc.slacknotifications.connectors

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector._
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class UserManagementConnectorSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val connector: UserManagementConnector =
    new UserManagementConnector(
      httpClientV2,
      new ServicesConfig(Configuration(
        "microservice.services.user-management.port" -> wireMockPort,
        "microservice.services.user-management.host" -> wireMockHost
      ))
    )

  "getGithubUser" should {
    "return a users details by github username" in {
      stubFor(
        get(urlEqualTo("/user-management/users?github=c-d"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """[
                  |   {
                  |     "displayName": "C D",
                  |     "familyName": "D",
                  |     "givenName": "C",
                  |     "organisation": "MDTP",
                  |     "primaryEmail": "c.d@digital.hmrc.gov.uk",
                  |     "username": "c.d",
                  |     "githubUsername": "c-d",
                  |     "role": "user",
                  |     "teamNames": [
                  |       "Team A",
                  |       "Team B"
                  |     ]
                  |   }
                  |]
                  |""".stripMargin
              )
          )
      )

      connector.getGithubUser("c-d").futureValue shouldBe List(User("c.d", None, Some("c-d"), "user", List(TeamName("Team A"), TeamName("Team B"))))
    }

    "return github user with no teams" in {
      stubFor(
        get(urlEqualTo("/user-management/users?github=c-d"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """[
                  |   {
                  |     "displayName": "C D",
                  |     "familyName": "D",
                  |     "givenName": "C",
                  |     "organisation": "MDTP",
                  |     "primaryEmail": "c.d@digital.hmrc.gov.uk",
                  |     "username": "c.d",
                  |     "githubUsername": "c-d",
                  |     "role" : "user",
                  |     "teamNames": []
                  |   }
                  |]
                  |""".stripMargin
              )
          )
      )

      connector.getGithubUser("c-d").futureValue shouldBe List(User("c.d", None, Some("c-d"), "user", List.empty[TeamName]))
    }

    "return empty list when github user not found" in {
      stubFor(
        get(urlEqualTo("/user-management/users?github=c-d"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """[]"""
              )
          )
      )
      connector.getGithubUser("c-d").futureValue shouldBe List.empty[User]
    }
  }

  "getLdapUser" should {
    "return a users details by ldap username" in {
      stubFor(
        get(urlEqualTo("/user-management/users/c.d"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """
                  |{
                  |  "displayName": "C D",
                  |  "familyName": "D",
                  |  "givenName": "C",
                  |  "organisation": "MDTP",
                  |  "primaryEmail": "c.d@digital.hmrc.gov.uk",
                  |  "username": "c.d",
                  |  "githubUsername": "c-d",
                  |  "role": "user",
                  |  "teamNames": [
                  |     "Team A",
                  |     "Team B"
                  |  ]
                  |}
                  |""".stripMargin
              )
          )
      )

      connector.getLdapUser("c.d").futureValue shouldBe Some(User("c.d", None, Some("c-d"), "user", List(TeamName("Team A"), TeamName("Team B"))))
    }

    "return ldap user with no teams" in {
      stubFor(
        get(urlEqualTo("/user-management/users/c.d"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """
                  |{
                  |  "displayName": "C D",
                  |  "familyName": "D",
                  |  "givenName": "C",
                  |  "organisation": "MDTP",
                  |  "primaryEmail": "c.d@digital.hmrc.gov.uk",
                  |  "username": "c.d",
                  |  "githubUsername": "c-d",
                  |  "role": "user",
                  |  "teamNames": []
                  |}
                  |""".stripMargin
              )
          )
      )

      connector.getLdapUser("c.d").futureValue shouldBe Some(User("c.d", None, Some("c-d"), "user", List.empty[TeamName]))
    }

    "return None when ldap user not found" in {
      stubFor(
        get(urlEqualTo("/user-management/users/c.d"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )
      connector.getLdapUser("c.d").futureValue shouldBe None
    }
  }

  "getTeamUsers" should {
    "return users for given team" in {
      stubFor(
        get(urlEqualTo("/user-management/users?team=TeamA"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """
                  |[
                  |  {
                  |    "displayName": "A",
                  |    "familyName": "A",
                  |    "givenName": "B",
                  |    "organisation": "MDTP",
                  |    "primaryEmail": "A.B@digital.hmrc.gov.uk",
                  |    "slackId": "id_A",
                  |    "username": "A.B",
                  |    "githubUsername": "ab",
                  |    "role": "user",
                  |    "teamNames": [
                  |      "TeamA"
                  |    ]
                  |  },
                  |  {
                  |    "displayName": "C D",
                  |    "familyName": "C",
                  |    "givenName": "D",
                  |    "organisation": "MDTP",
                  |    "primaryEmail": "c.d@digital.hmrc.gov.uk",
                  |    "username": "C.D",
                  |    "githubUsername": "cd",
                  |    "role": "team_admin",
                  |    "teamNames": [
                  |      "TeamA"
                  |    ]
                  |  }
                  |]
                  |""".stripMargin
              )
          )
      )

      connector.getTeamUsers("TeamA").futureValue shouldBe
        Seq(
          User(ldapUsername = "A.B"
          , slackId = Some("id_A")
          , githubUsername = Some("ab")
          , role = "user"
          , teamNames = List(TeamName("TeamA"))
          ),
          User(ldapUsername = "C.D"
          , slackId = None
          , githubUsername = Some("cd")
          , role = "team_admin"
          , teamNames = List(TeamName("TeamA"))
          ),
        )

    }
  }

  "getTeamSlackDetails" should {
    "return slack details for a team" in {
      stubFor(
        get(urlEqualTo("/user-management/teams/TeamA"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(
                """{
                  |  "members": [
                  |    {
                  |      "username": "joe.bloggs",
                  |      "displayName": "Joe Bloggs",
                  |      "role": "user"
                  |    },
                  |    {
                  |      "username": "jane.doe",
                  |      "displayName": "Jane Doe",
                  |      "role": "user"
                  |    }
                  |  ],
                  |  "teamName": "TeamA",
                  |  "description": "Team A",
                  |  "documentation": "https://confluence.tools.tax.service.gov.uk/display/TeamA",
                  |  "slack": "https://slack.com/messages/team-a",
                  |  "slackNotification": "https://slack.com/messages/team-a-alerts"
                  |}
                  |""".stripMargin
              )
          )
      )
        connector.getTeamSlackDetails("TeamA").futureValue shouldBe Some(TeamDetails("TeamA", Some("https://slack.com/messages/team-a"), Some("https://slack.com/messages/team-a-alerts")))
    }

    "return None when team not found" in {
      stubFor(
        get(urlEqualTo("/user-management/teams/TeamA"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )
      connector.getTeamSlackDetails("TeamA").futureValue shouldBe None
    }
  }
}
