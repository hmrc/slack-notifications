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
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamName, User}
import uk.gov.hmrc.slacknotifications.base.UnitSpec
import org.mockito.Mockito.when
import org.mockito.ArgumentMatchers.any

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UserManagementServiceSpec
  extends UnitSpec
  with ScalaFutures:

  given HeaderCarrier = HeaderCarrier()

  "getTeamsForLdapUser" should:
    "return list of team names" in new Fixtures:

      val ldapUserWithTeams: User =
        User("ldapUsername", None, Some("githubUsername"), "user", List(TeamName("Team A"), TeamName("Team B")))

      when(userManageConnector.getLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(ldapUserWithTeams)))

      service.getTeamsForLdapUser("ldapUsername").futureValue shouldBe List(TeamName("Team A"), TeamName("Team B"))

    "return empty list when no user details found" in new Fixtures:
      when(userManageConnector.getLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(None))

      service.getTeamsForLdapUser("ldapUsername").futureValue shouldBe List.empty[TeamName]

    "return empty list when user has no team details" in new Fixtures:
      val ldapUserNoTeams: User =
        User("ldapUsername", None, Some("githubUsername"), "user", List.empty[TeamName])

      when(userManageConnector.getLdapUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(ldapUserNoTeams)))

      service.getTeamsForLdapUser("ldapUsername").futureValue shouldBe List.empty[TeamName]

  "getTeamsForGithubUser" should:
    "return list of team names" in new Fixtures:

      val githubUserWithTeams: User =
        User("ldapUsername", None, Some("githubUsername"), "user", List(TeamName("Team A"), TeamName("Team B")))

      when(userManageConnector.getGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(List(githubUserWithTeams)))

      service.getTeamsForGithubUser("githubUsername").futureValue shouldBe List(TeamName("Team A"), TeamName("Team B"))

    "return empty list when no user details found" in new Fixtures:
      when(userManageConnector.getGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(List.empty))

      service.getTeamsForGithubUser("githubUsername").futureValue shouldBe List.empty[TeamName]

    "return empty list when user has no team details" in new Fixtures:
      val githubUserNoTeams: User =
        User("ldapUsername", None, Some("githubUsername"), "user", List.empty[TeamName])

      when(userManageConnector.getGithubUser(any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(List(githubUserNoTeams)))

      service.getTeamsForGithubUser("githubUsername").futureValue shouldBe List.empty[TeamName]

  trait Fixtures:
    val userManageConnector: UserManagementConnector = mock[UserManagementConnector]
    lazy val service: UserManagementService = UserManagementService(userManageConnector)
