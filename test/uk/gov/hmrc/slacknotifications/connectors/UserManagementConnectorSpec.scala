/*
 * Copyright 2020 HM Revenue & Customs
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

import akka.actor.ActorSystem
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import play.api.{Configuration, Environment, Play}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.http.ws.WSHttp
import com.github.tomakehurst.wiremock.client.WireMock._
import com.typesafe.config.Config
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector._
import scala.concurrent.ExecutionContext.Implicits.global

class UserManagementConnectorSpec
    extends WordSpec
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with BeforeAndAfterEach
    with GuiceOneAppPerSuite
    with IntegrationPatience {

  val Port           = 8080
  val Host           = "localhost"
  val wireMockServer = new WireMockServer(wireMockConfig().port(Port))

  override def beforeEach {
    wireMockServer.start()
    WireMock.configureFor(Host, Port)
  }

  override def afterEach {
    wireMockServer.stop()
  }

  val httpClient = new HttpClient with WSHttp {
    override val hooks: Seq[HttpHook] = Seq.empty

    override protected def actorSystem: ActorSystem = Play.current.actorSystem

    override protected def configuration: Option[Config] = Option(Play.current.configuration.underlying)
  }

  "The connector" should {

    implicit val hc = HeaderCarrier()
    val ump = new UserManagementConnector(
      httpClient,
      Configuration("microservice.services.user-management.url" -> s"http://$Host:$Port"),
      Environment.simple())

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
