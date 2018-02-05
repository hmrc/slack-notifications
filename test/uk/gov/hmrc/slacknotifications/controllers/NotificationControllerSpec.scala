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

package uk.gov.hmrc.slacknotifications.controllers

import akka.util.Timeout
import cats.data.NonEmptyList
import cats.data.Validated.Valid
import cats.implicits._
import concurrent.ExecutionContext.Implicits.global
import concurrent.Future
import concurrent.duration._
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsString, contentType, status}
import uk.gov.hmrc.slacknotifications.services.NotificationService
import uk.gov.hmrc.slacknotifications.services.NotificationService.{RepositoryNotFound, SlackChannelNotFoundForTeam}

class NotificationControllerSpec extends WordSpec with Matchers with MockitoSugar with ScalaFutures {

  implicit val timeout = Timeout(10.seconds)

  "Controller" should {
    "return 200 success if slack message sent successfully" in {
      val mockedNotificationService = mock[NotificationService]
      val controller                = new NotificationController(mockedNotificationService)

      when(mockedNotificationService.sendNotification(any())(any())).thenReturn(Future(Valid(())))

      val jsonBody = Json.parse(
        """
          {
            "channelLookup" : {
              "by" : "github-repository",
              "repositoryName" : "foo"
            },
            "messageDetails" : {
              "text" : "a slack message",
              "username" : "a-user",
              "attachments" : []
            }
          }
        """
      )
      val response = controller.sendNotification()(FakeRequest().withBody(jsonBody))

      withClue(s"Response was: ${contentAsString(response)}") {
        status(response) shouldBe 200
      }
    }

    "return 400 bad request if specified repository doesn't exist" in {
      val mockedNotificationService = mock[NotificationService]
      val controller                = new NotificationController(mockedNotificationService)
      val repoName                  = "foo"
      val jsonBody = Json.parse(
        s"""
          {
            "channelLookup" : {
              "by" : "github-repository",
              "repositoryName" : "$repoName"
            },
            "messageDetails" : {
              "text" : "a slack message",
              "username" : "a-user",
              "attachments" : [
                {
                  "text" : "my-attachment"
                }
              ]
            }
          }
        """
      )
      when(mockedNotificationService.sendNotification(any())(any()))
        .thenReturn(Future(RepositoryNotFound(repoName).invalidNel))

      val response = controller.sendNotification()(FakeRequest().withBody(jsonBody))

      withClue(s"Response was: ${contentAsString(response)}") {
        status(response)          shouldBe 400
        contentType(response).get shouldBe "application/json"
        contentAsString(response) should include(s"Repository: '$repoName' not found")
      }
    }

    "return 500 internal server error with a list of errors if other errors occur" in {
      val mockedNotificationService = mock[NotificationService]
      val controller                = new NotificationController(mockedNotificationService)
      val jsonBody = Json.parse(
        s"""
          {
            "channelLookup" : {
              "by" : "github-repository",
              "repositoryName" : "a-repository"
            },
            "messageDetails" : {
              "text" : "a slack message",
              "username" : "a-user",
              "iconEmoji" : ":monkey_face:"
            }
          }
        """
      )

      val teamName = "team 1"

      when(mockedNotificationService.sendNotification(any())(any()))
        .thenReturn(Future(NonEmptyList.of(SlackChannelNotFoundForTeam(teamName)).invalid))

      val response = controller.sendNotification()(FakeRequest().withBody(jsonBody))

      status(response)          shouldBe 500
      contentType(response).get shouldBe "application/json"
      contentAsString(response) should include(
        s""""errors":["Slack channel not found for team: '$teamName'"]""".stripMargin)

    }

  }

}
