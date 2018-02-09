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
import concurrent.duration._
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, WordSpec}
import play.api.Configuration
import play.api.libs.json.{JsValue, Json}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.http.{BadRequestException, HeaderCarrier, HttpResponse, NotFoundException, _}
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, SlackConnector, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.{GithubRepository, SlackChannel}
import uk.gov.hmrc.slacknotifications.model.{MessageDetails, NotificationRequest, SlackMessage}
import uk.gov.hmrc.slacknotifications.services.NotificationService._

class NotificationServiceSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar with PropertyChecks {

  implicit val hc: HeaderCarrier                       = HeaderCarrier()
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.second, 15.millis)

  "Sending a Slack message" should {
    "succeed if slack accepted the notification" in new Fixtures {
      when(slackConnector.sendMessage(any())(any())).thenReturn(Future(HttpResponse(200)))

      private val existingChannel = "existingChannel"
      val result = service
        .sendSlackMessage(
          SlackMessage(
            channel     = existingChannel,
            text        = "text",
            username    = "someUser",
            icon_emoji  = Some(":snowman:"),
            attachments = Nil))
        .futureValue

      result shouldBe NotificationResult().addSuccessfullySent(existingChannel)
    }

    "return error if response was not 200" in new Fixtures {
      val invalidStatusCodes = Gen.choose(201, 599)
      forAll(invalidStatusCodes) { statusCode =>
        val errorMsg = "invalid_payload"
        when(slackConnector.sendMessage(any())(any()))
          .thenReturn(Future(HttpResponse(statusCode, responseString = Some(errorMsg))))

        val result = service
          .sendSlackMessage(
            SlackMessage(
              channel     = "nonexistentChannel",
              text        = "text",
              username    = "someUser",
              icon_emoji  = Some(":snowman:"),
              attachments = Nil))
          .futureValue

        result shouldBe NotificationResult().addError(SlackError(statusCode, errorMsg))
      }
    }

    "return an error if an exception was thrown by the Slack connector" in new Fixtures {
      val errorMsg = "some exception message"

      val exceptionsAndErrors =
        Table(
          ("exception", "expected error"),
          (new BadRequestException(errorMsg), SlackError(400, errorMsg)),
          (Upstream4xxResponse(errorMsg, 403, 403), SlackError(403, errorMsg)),
          (Upstream5xxResponse(errorMsg, 500, 500), SlackError(500, errorMsg)),
          (new NotFoundException(errorMsg), SlackError(404, errorMsg))
        )

      forAll(exceptionsAndErrors) { (exception, expectedError) =>
        when(slackConnector.sendMessage(any())(any()))
          .thenReturn(Future.failed(exception))

        val result = service
          .sendSlackMessage(
            SlackMessage(
              channel     = "channel",
              text        = "text",
              username    = "someUser",
              icon_emoji  = Some(":snowman:"),
              attachments = Nil))
          .futureValue

        result.errors.head shouldBe expectedError
      }
    }

    "throw an exception if any other non-fatal exception was thrown" in new Fixtures {
      val errorMsg = "non-fatal exception"

      when(slackConnector.sendMessage(any())(any()))
        .thenReturn(Future.failed(new RuntimeException(errorMsg)))

      val thrown =
        the[RuntimeException] thrownBy {
          service
            .sendSlackMessage(
              SlackMessage(
                channel     = "channel",
                text        = "text",
                username    = "someUser",
                icon_emoji  = Some(":snowman:"),
                attachments = Nil))
            .futureValue
        }

      thrown.getCause.getMessage shouldBe errorMsg
    }
  }

  "Sending a notification" should {

    "succeed (happy path)" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List(teamName)))))

      val teamChannel = "team-channel"
      val teamDetails = s""" { "slack" : "https://foo.slack.com/$teamChannel" } """

      val channelLookups = List(
        GithubRepository("", "repo"),
        SlackChannel("", NonEmptyList.of(teamChannel))
      )

      channelLookups.foreach { channelLookup =>
        val notificationRequest =
          NotificationRequest(
            channelLookup = channelLookup,
            messageDetails = MessageDetails(
              text        = "some-text-to-post-to-slack",
              username    = "username",
              iconEmoji   = Some(":snowman:"),
              attachments = Nil
            )
          )

        when(userManagementConnector.getTeamSlackChannel(any())(any()))
          .thenReturn(Future(HttpResponse(responseStatus = 200, responseJson = Some(Json.parse(teamDetails)))))

        when(slackConnector.sendMessage(any())(any())).thenReturn(Future(HttpResponse(200)))

        val result = service.sendNotification(notificationRequest).futureValue

        result shouldBe NotificationResult(
          successfullySentTo = List(teamChannel),
          errors             = Nil,
          exclusions         = Nil
        )
      }

    }

    "don't send notifications for a predefined set of teams" in new Fixtures {
      private val teamName1 = "team-to-be-excluded-1"
      private val teamName2 = "team-to-be-excluded-2"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List(teamName1, teamName2)))))

      override val configuration = Configuration("exclusions.notRealTeams" -> s"$teamName1, $teamName2")

      val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", "repo"),
          messageDetails = MessageDetails(
            text        = "some-text-to-post-to-slack",
            username    = "username",
            iconEmoji   = Some(":snowman:"),
            attachments = Nil
          )
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = Nil,
        exclusions         = List(NotARealTeam(teamName1), NotARealTeam(teamName2))
      )
    }

    "report if a repository does not exist" in new Fixtures {
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any())).thenReturn(Future(None))
      private val nonexistentRepoName = "nonexistent-repo"
      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", nonexistentRepoName),
          messageDetails = MessageDetails(
            text        = "some-text-to-post-to-slack",
            username    = "username",
            iconEmoji   = Some(":snowman:"),
            attachments = Nil
          )
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(RepositoryNotFound(nonexistentRepoName)),
        exclusions         = Nil
      )
    }

    "report if the team name is not found by the user management service" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List(teamName)))))
      when(userManagementConnector.getTeamSlackChannel(any())(any()))
        .thenReturn(Future(HttpResponse(responseStatus = 200, responseJson = Some(Json.obj()))))

      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", ""),
          messageDetails = MessageDetails(
            text        = "some-text-to-post-to-slack",
            username    = "username",
            iconEmoji   = Some(":snowman:"),
            attachments = Nil)
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(SlackChannelNotFoundForTeam(teamName)),
        exclusions         = Nil
      )
    }

    "report if no team is assigned to a repository" in new Fixtures {
      private val teamName = "team-name"
      when(teamsAndRepositoriesConnector.getRepositoryDetails(any())(any()))
        .thenReturn(Future(Some(RepositoryDetails(teamNames = List()))))

      val repoName = "repo-name"
      private val notificationRequest =
        NotificationRequest(
          channelLookup = GithubRepository("", repoName),
          messageDetails = MessageDetails(
            text        = "some-text-to-post-to-slack",
            username    = "username",
            iconEmoji   = Some(":snowman:"),
            attachments = Nil
          )
        )

      val result = service.sendNotification(notificationRequest).futureValue

      result shouldBe NotificationResult(
        successfullySentTo = Nil,
        errors             = List(TeamsNotFoundForRepository(repoName)),
        exclusions         = Nil
      )
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

    "return None if slack field does not contain a forward slash" in new Fixtures {
      service.extractSlackChannel(Json.obj("slack" -> "not a url")) shouldBe None
    }
  }

  trait Fixtures {
    val slackConnector                = mock[SlackConnector]
    val teamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val userManagementConnector       = mock[UserManagementConnector]
    val configuration                 = Configuration()

    lazy val service =
      new NotificationService(configuration, slackConnector, teamsAndRepositoriesConnector, userManagementConnector)
  }

}
