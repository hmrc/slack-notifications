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

import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, ServiceConfigsConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.{Error, NotificationResult}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ChannelLookupServiceSpec
  extends UnitSpec {

  "Getting teams responsible for repo" should {
    "prioritize owningTeams" in new Fixtures {
      service.getTeamsResponsibleForRepo(repoDetails) shouldBe List("team1")
    }

    "return contributing teams if no explicit owning teams are specified" in new Fixtures {
      service.getTeamsResponsibleForRepo(repoDetails.copy(owningTeams = Nil)) shouldBe List("team1", "team2")
    }
  }

  "Extracting team slack channel" should {
    "work if slack field exists and contains team name at the end" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink = "foo/" + teamChannelName
      val teamDetails = TeamDetails(slack = Some(slackLink), slackNotification = None, teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(teamChannelName)
    }

    "return the slackNotification channel when present" in new Fixtures {
      val teamChannelName = "teamChannel"
      val slackLink = "foo/" + teamChannelName
      val teamDetails = TeamDetails(
        slack = Some(slackLink),
        slackNotification = Some(s"foo/$teamChannelName-notification"),
        teamName = "n/a")

      service.extractSlackChannel(teamDetails) shouldBe Some(s"$teamChannelName-notification")
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

  "Get existing repository" should {
    "return repository details for a service" in new Fixtures  {
      when(mockTeamsAndReposConn.getRepositoryDetails(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(Some(repoDetails)))

      service.getExistingRepository(eqTo(serviceName))(any[HeaderCarrier]).futureValue shouldBe Right(repoDetails)
    }

    "return a notification result when no repository details are found for a service" in new Fixtures {
      when(mockTeamsAndReposConn.getRepositoryDetails(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(None))
      when(mockServiceConfigsConnector.repoNameForService(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(None))

      val result = service.getExistingRepository(eqTo("service"))(any[HeaderCarrier]).futureValue
      result shouldBe Left(NotificationResult(Seq.empty, Seq(Error.repositoryNotFound(serviceName)), Seq.empty))
    }

    "return repository details for a service that has a different repo name" in new Fixtures {
      val repoNameForService = "repo"

      when(mockTeamsAndReposConn.getRepositoryDetails(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(None))
      when(mockServiceConfigsConnector.repoNameForService(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(Some(repoNameForService)))
      when(mockTeamsAndReposConn.getRepositoryDetails(eqTo(repoNameForService))(any[HeaderCarrier]))
       .thenReturn(Future.successful(Some(repoDetails)))

      service.getExistingRepository(eqTo(serviceName))(any[HeaderCarrier]).futureValue shouldBe Right(repoDetails)
    }

    "return a notification result when a service has a different repo name but the repository details are not found" in new Fixtures {
      val repoNameForService = "repo"

      when(mockTeamsAndReposConn.getRepositoryDetails(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(None))
      when(mockServiceConfigsConnector.repoNameForService(eqTo(serviceName))(any[HeaderCarrier]))
       .thenReturn(Future.successful(Some(repoNameForService)))
      when(mockTeamsAndReposConn.getRepositoryDetails(eqTo(repoNameForService))(any[HeaderCarrier]))
       .thenReturn(Future.successful(None))

      val result = service.getExistingRepository(eqTo("service"))(any[HeaderCarrier]).futureValue
      result shouldBe Left(NotificationResult(Seq.empty, Seq(Error.repositoryNotFound(repoNameForService)), Seq.empty))
    }
  }

  trait Fixtures {

    val mockSlackConfig            : SlackConfig                   = mock[SlackConfig]
    val mockTeamsAndReposConn      : TeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val mockUserManagementConnector: UserManagementConnector       = mock[UserManagementConnector]
    val mockServiceConfigsConnector: ServiceConfigsConnector       = mock[ServiceConfigsConnector]

    lazy val service: ChannelLookupService =
      new ChannelLookupService(
        mockSlackConfig,
        mockTeamsAndReposConn,
        mockUserManagementConnector,
        mockServiceConfigsConnector
      )

    val serviceName = "service"

    val repoDetails: RepositoryDetails =
      RepositoryDetails(
        owningTeams = List("team1"),
        teamNames   = List("team1", "team2")
      )
  }

}
