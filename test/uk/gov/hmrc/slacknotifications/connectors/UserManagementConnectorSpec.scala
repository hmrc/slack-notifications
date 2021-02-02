/*
 * Copyright 2021 HM Revenue & Customs
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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector._
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global

class UserManagementConnectorSpec
    extends UnitSpec
    with ScalaFutures
    with BeforeAndAfterEach
    with GuiceOneAppPerSuite
    with IntegrationPatience {

  private val Port           = 8080
  private val Host           = "localhost"
  private val wireMockServer = new WireMockServer(wireMockConfig().port(Port))
  private val servicesConfig = new ServicesConfig(
    Configuration("microservice.services.user-management.url" -> s"http://$Host:$Port")
  )
  private val httpClient = app.injector.instanceOf[HttpClient]

  override def beforeEach {
    wireMockServer.start()
    WireMock.configureFor(Host, Port)
  }

  override def afterEach {
    wireMockServer.stop()
  }

  "The connector" should {

    implicit val hc = HeaderCarrier()

    val ump = new UserManagementConnector(httpClient, servicesConfig)

    "getAllUsers of the organisation" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/users"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""{ "users": [{ "github": "https://github.com/abc", "username": "abc" } ]}""")))

      ump.getAllUsers.futureValue shouldBe List(UmpUser(Some("https://github.com/abc"), Some("abc")))
    }

    "get all the teams for a specified user" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/users/ldapUsername/teams"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                  |{
                  |  "teams": [
                  |    {
                  |      "slack": "foo/team-A",
                  |      "slackNotification": "foo/team-A-notifications",
                  |      "team": "team-A"
                  |    }
                  |  ]
                  |}""".stripMargin)))

      ump.getTeamsForUser("ldapUsername").futureValue shouldBe List(
        TeamDetails(Some("foo/team-A"), Some("foo/team-A-notifications"), "team-A"))
    }

    "return an empty list of teams if the user has no teams" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/users/ldapUsername/teams"))
          .willReturn(
            aResponse()
              .withStatus(404)
              .withBody("""
                  |{
                  |reason: "Not Found"
                  |}""".stripMargin)))

      ump.getTeamsForUser("ldapUsername").futureValue shouldBe List.empty
    }

    "get team details" in {

      stubFor(
        get(urlEqualTo("/v2/organisations/teams/team-A"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                  |{
                  |  "slack": "foo/team-A",
                  |  "slackNotification": "foo/team-A-notifications",
                  |  "team": "team-A"
                  |}
                  |""".stripMargin)))

      ump.getTeamDetails("team-A").futureValue shouldBe
        Some(TeamDetails(Some("foo/team-A"), Some("foo/team-A-notifications"), "team-A"))
    }

  }

}
