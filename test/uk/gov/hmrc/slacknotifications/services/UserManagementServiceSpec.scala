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

import org.mockito.MockitoSugar.mock
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamName, User}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UserManagementServiceSpec extends UnitSpec with ScalaFutures with IntegrationPatience {

  private implicit val hc = HeaderCarrier()

  "getTeamsLdapUser" should {
    "return all teams an ldap user belongs to" in new Setup {

      val user =
        User(
          ldapUsername   = Some("ldap.username"),
          githubUsername = Some("github-username"),
          teams          = Seq(TeamName("TeamA"), TeamName("TeamB"))
        )

      when(userManagementConnector.getLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(user)))

      service.getTeamsForLdapUser("ldap.username").futureValue shouldBe user.teams
    }

    "return empty list of teams when ldap user is not found" in new Setup {
      when(userManagementConnector.getLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))

      service.getTeamsForLdapUser("ldap.username").futureValue shouldBe List.empty[TeamName]
    }

    "return empty list when ldap user exists but has no teams" in new Setup {
      val user =
        User(
          ldapUsername   = Some("ldap.username"),
          githubUsername = Some("github-username"),
          teams          = Seq.empty
        )

      when(userManagementConnector.getLdapUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(user)))

      service.getTeamsForLdapUser("ldap.username").futureValue shouldBe Seq.empty
    }



    "return all teams a github user belongs to" in new Setup {

      val user =
        User(
          ldapUsername   = Some("ldap.username"),
          githubUsername = Some("github-username"),
          teams          = Seq(TeamName("TeamA"), TeamName("TeamB"))
        )

      when(userManagementConnector.getGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(user)))

      service.getTeamsForGithubUser("github-username").futureValue shouldBe user.teams
    }

    "return empty list of teams when github user is not found" in new Setup {
      when(userManagementConnector.getGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(None))

      service.getTeamsForGithubUser("github-username").futureValue shouldBe List.empty[TeamName]
    }

    "return empty list when github user exists but has no teams" in new Setup {
      val user =
        User(
          ldapUsername   = Some("ldap.username"),
          githubUsername = Some("github-username"),
          teams          = Seq.empty
        )

      when(userManagementConnector.getGithubUser(any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(user)))

      service.getTeamsForGithubUser("github-username").futureValue shouldBe Seq.empty
    }

  }
}

trait Setup {
  val userManagementConnector = mock[UserManagementConnector]
  val service                 = new UserManagementService(userManagementConnector)
}
