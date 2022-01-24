/*
 * Copyright 2022 HM Revenue & Customs
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

import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Headers, Result}
import play.api.test.{FakeRequest, StubControllerComponentsFactory}
import play.test.Helpers
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.model.{NotificationRequest, Password}
import uk.gov.hmrc.slacknotifications.services.AuthService.Service
import uk.gov.hmrc.slacknotifications.services.NotificationService.NotificationResult
import uk.gov.hmrc.slacknotifications.services.{AuthService, NotificationService}
import uk.gov.hmrc.slacknotifications.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NotificationControllerSpec extends UnitSpec with ScalaFutures {

  "The controller" should {
    "allow requests with valid credentials in the Authorization header" in new TestSetup {
      val validCredentials = "Zm9vOmJhcg==" // foo:bar:deployments-info base64 encoded
      val request          = baseRequest.withHeaders("Authorization" -> s"Basic $validCredentials")

      when(notificationService.sendNotification(any[NotificationRequest], any[Service])(any[HeaderCarrier]))
        .thenReturn(Future.successful(NotificationResult()))
      when(authService.isAuthorized(eqTo(Service("foo", Password("bar"))))).thenReturn(true)

      val response: Result = controller.sendNotification().apply(request).futureValue
      response.header.status shouldBe 200
    }

    "stop requests with no Authorization header" in new TestSetup {
      val response: Result = controller.sendNotification().apply(baseRequest).futureValue
      response.header.status shouldBe 401
    }

    "block requests with invalid credentials in the Authorization header" in new TestSetup {
      val invalidCredentials = "Zm9vOmJhcg==" // foo:bar base64 encoded
      val request            = baseRequest.withHeaders("Authorization" -> s"Basic $invalidCredentials")

      when(authService.isAuthorized(eqTo(Service("foo", Password("bar"))))).thenReturn(false)

      val response: Result = controller.sendNotification().apply(request).futureValue
      response.header.status shouldBe 401
    }
  }

  private trait TestSetup extends StubControllerComponentsFactory {

    val authService         = mock[AuthService]
    val notificationService = mock[NotificationService]
    val controller = new NotificationController(authService, notificationService, stubControllerComponents())

    val body =
      """{
        "channelLookup" : {
            "by" : "github-repository",
            "repositoryName" : "name-of-a-repo"
        },
        "messageDetails" : {
            "text" : "message to be posted"
        }
      }"""

    val baseRequest =
      FakeRequest[JsValue](
        Helpers.POST,
        "/slack-notifications/notification",
        Headers("Content-Type" -> "application/json"),
        Json.parse(body)
      )
  }
}
