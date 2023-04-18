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

import akka.Done
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import play.api.Configuration
import play.api.cache.AsyncCacheApi
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.slacknotifications.config.UserManagementAuthConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector._
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

class UserManagementConnectorSpec
  extends UnitSpec
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support {

  "The connector" should {
    implicit val hc = HeaderCarrier()

    val userManagementConnector = {
      val servicesConfig =
        new ServicesConfig(
          Configuration("microservice.services.user-management.url" -> wireMockUrl)
        )

      val umpAuthConfig = new UserManagementAuthConfig(Configuration("ump.auth.enabled" -> false))

      val cache = new AsyncCacheApi {
        override def set(key: String, value: Any, expiration: Duration): Future[Done] = ???

        override def remove(key: String): Future[Done] = ???

        override def getOrElseUpdate[A](key: String, expiration: Duration)(orElse: =>Future[A])(implicit evidence$1: ClassTag[A]): Future[A] = ???

        override def get[T](key: String)(implicit evidence$2: ClassTag[T]): Future[Option[T]] = ???

        override def removeAll(): Future[Done] = ???
      }

      new UserManagementConnector(httpClientV2, servicesConfig, umpAuthConfig, cache)
    }

    "getAllUsers of the organisation" in {
      stubFor(
        get(urlEqualTo("/v2/organisations/users"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""{
                "users": [
                  {
                    "github": "https://github.com/abc",
                    "username": "abc"
                  }
                ]}"""
              )
          )
      )

      userManagementConnector.getAllUsers.futureValue shouldBe List(UmpUser(Some("https://github.com/abc"), Some("abc")))
    }

    "get all the teams for a specified user" in {
      stubFor(
        get(urlEqualTo("/v2/organisations/users/ldapUsername/teams"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                {
                  "teams": [
                    {
                      "slack": "foo/team-A",
                      "slackNotification": "foo/team-A-notifications",
                      "team": "team-A"
                    }
                  ]
                }"""
              )
          )
      )

      userManagementConnector.getTeamsForUser("ldapUsername").futureValue shouldBe List(
        TeamDetails(Some("foo/team-A"), Some("foo/team-A-notifications"), "team-A"))
    }

    "return an empty list of teams if the user has no teams" in {
      stubFor(
        get(urlEqualTo("/v2/organisations/users/ldapUsername/teams"))
          .willReturn(
            aResponse()
              .withStatus(404)
              .withBody("""
                {
                  reason: "Not Found"
                }"""
              )
          )
      )

      userManagementConnector.getTeamsForUser("ldapUsername").futureValue shouldBe List.empty
    }

    "get team details" in {
      stubFor(
        get(urlEqualTo("/v2/organisations/teams/team-A"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                {
                  "slack": "foo/team-A",
                  "slackNotification": "foo/team-A-notifications",
                  "team": "team-A"
                }"""
              )
          )
      )

      userManagementConnector.getTeamDetails("team-A").futureValue shouldBe
        Some(TeamDetails(Some("foo/team-A"), Some("foo/team-A-notifications"), "team-A"))
    }
  }
}
