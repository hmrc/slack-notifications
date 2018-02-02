/*
 * Copyright 2018 HM Revenue & Customs
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

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import concurrent.duration._
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.{JsValue, Json}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.GithubRepository
import uk.gov.hmrc.slacknotifications.model.{NotificationRequest, SlackMessage}
import uk.gov.hmrc.slacknotifications.services.NotificationService.{RepositoryNotFound, SlackChannelNotFound, TeamsNotFoundForRepository}

class NotificationServiceSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar with PropertyChecks {

  implicit val hc: HeaderCarrier                       = HeaderCarrier()
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(1.second, 15.millis)

  "Sending a Slack message" should {
    "succeed if slack accepted the notification" in new Fixtures {
      when(slackConnector.sendMessage(any())(any())).thenReturn(Future(HttpResponse(200)))

      val result = service.sendSlackMessage(SlackMessage("existentChannel")).futureValue

      result shouldBe Right(())
    }

    "return error if response was not 200" in new Fixtures {
      val invalidStatusCodes = Gen.choose(201, 599)
      forAll(invalidStatusCodes) { statusCode =>
        val errorMsg = "invalid_payload"
        when(slackConnector.sendMessage(any())(any()))
          .thenReturn(Future(HttpResponse(statusCode, responseString = Some(errorMsg))))

        val result = service.sendSlackMessage(SlackMessage("nonexistentChannel")).futureValue

        result shouldBe Left(NotificationService.OtherError(statusCode, errorMsg))
      }
    }
  }

  "Sending a notification" should {

    "success (happy path)" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List(teamName)))))

      val teamChannel = "team-channel"
      val teamDetails = s""" { "slack" : "https://foo.slack.com/$teamChannel" } """

      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", "repo"),
          text          = "some-text-to-post-to-slack"
        )

      when(userManagementConnector.getTeamSlackChannel(any())(any()))
        .thenReturn(Future(HttpResponse(responseStatus = 200, responseJson = Some(Json.parse(teamDetails)))))

      when(slackConnector.sendMessage(any())(any())).thenReturn(Future(HttpResponse(200)))

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe Valid(())
    }

    "fail if requested to lookup a channel for repository that doesn't exist" in new Fixtures {
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any())).thenReturn(Future(None))
      private val nonexistentRepoName = "nonexistent-repo"
      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", nonexistentRepoName),
          text          = "some-text-to-post-to-slack"
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe Invalid(NonEmptyList.of(RepositoryNotFound(nonexistentRepoName)))
    }

    "fail if the team name is not found by the user management service" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List(teamName)))))
      when(userManagementConnector.getTeamSlackChannel(any())(any()))
        .thenReturn(Future(HttpResponse(responseStatus = 200, responseJson = Some(Json.obj()))))

      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", ""),
          text          = "some-text-to-post-to-slack"
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe Invalid(NonEmptyList.of(SlackChannelNotFound(teamName)))
    }

    "fail if no team is assigned to a repository" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List()))))

      val repoName = "repo-name"
      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", repoName),
          text          = "some-text-to-post-to-slack"
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe Invalid(NonEmptyList.of(TeamsNotFoundForRepository(repoName)))
    }
  }

  "Extracting team slack channel" should {
    "work if slack field exists and contains team name at the end" in new Fixtures {
      val teamName      = "teamName"
      val slackLink     = "foo/" + teamName
      val json: JsValue = Json.obj("slack" -> slackLink)

      service.extractSlackChannel(json) shouldBe Some(teamName)
    }

    "return None if slack field exists but there is no slack channel in it" in new Fixtures {
      val slackLink     = "link-without-team/"
      val json: JsValue = Json.obj("slack" -> slackLink)

      service.extractSlackChannel(json) shouldBe None
    }

    "return None if slack field doesn't exist" in new Fixtures {
      service.extractSlackChannel(Json.obj()) shouldBe None
    }
  }

  trait Fixtures {
    val slackConnector                = mock[SlackConnector]
    val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val userManagementConnector       = mock[UserManagementConnector]

    val service = new NotificationService(slackConnector, teamsAndRepositoriesConnector, userManagementConnector)
  }

}
