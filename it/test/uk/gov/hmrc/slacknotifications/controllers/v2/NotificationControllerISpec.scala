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

package uk.gov.hmrc.slacknotifications.controllers.v2

import akka.stream.Materializer
import akka.util.Timeout
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, containing, equalTo, get, post, stubFor, urlEqualTo}
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc.ControllerComponents
import play.api.test.Helpers
import play.api.test.Helpers.stubControllerComponents
import play.api.{Application, inject}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.internalauth.client.test.{BackendAuthComponentsStub, StubBehaviour}
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, Retrieval}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.slacknotifications.persistence.SlackMessageQueueRepository
import uk.gov.hmrc.slacknotifications.services.SlackMessageConsumer

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NotificationControllerISpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with Eventually
     with WireMockSupport
     with GuiceOneServerPerSuite {

  implicit val timout: Timeout = Helpers.defaultNegativeTimeout.t

  val authStubBehaviour: StubBehaviour = mock[StubBehaviour]
  when(authStubBehaviour.stubAuth(any, eqTo(Retrieval.EmptyRetrieval)))
    .thenReturn(Future.unit)

  implicit val cc: ControllerComponents = stubControllerComponents()

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(
      "microservice.services.internal-auth.host"          -> wireMockHost,
      "microservice.services.internal-auth.port"          -> wireMockPort,
      "microservice.services.teams-and-repositories.host" -> wireMockHost,
      "microservice.services.teams-and-repositories.port" -> wireMockPort,
      "microservice.services.user-management.host"        -> wireMockHost,
      "microservice.services.user-management.port"        -> wireMockPort,
      "slack.webhookUrl"                                  -> wireMockUrl,
      "slack.apiUrl"                                      -> wireMockUrl,
      "slack.botToken"                                    -> "token",
//      "slackMessageScheduler.enabled"                     -> true,
//      "slackMessageScheduler.initialDelay"                -> "1.seconds",
//      "slackMessageScheduler.interval"                    -> "1.seconds",
//      "slackMessageScheduler.lockTtl"                     -> "1.minute"
    )
    .overrides(
      inject.bind[BackendAuthComponents].toInstance(BackendAuthComponentsStub(authStubBehaviour))
    )
    .build()

  implicit val mat: Materializer = app.injector.instanceOf[Materializer]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val wsClient  = app.injector.instanceOf[WSClient]
  private val consumer  = app.injector.instanceOf[SlackMessageConsumer]
  private val queueRepo = app.injector.instanceOf[SlackMessageQueueRepository]
  private val baseUrl   = s"http://localhost:$port"

  "POST /v2/notification" should {
    "queue message for processing and return a msgId - scheduler should send message successfully" in {

      stubFor(
        post(urlEqualTo("/chat.postMessage"))
          .withHeader("Authorization", equalTo("Bearer token"))
          .willReturn(aResponse().withStatus(200).withBody("{}"))
      )

      val payload =
        Json.obj(
          "displayName"   -> JsString("my bot"),
          "emoji"         -> JsString(":robot_face:"),
          "channelLookup" -> Json.obj(
            "by"            -> JsString("slack-channel"),
            "slackChannels" -> JsArray(Seq(JsString("a-channel")))
          ),
          "text"        -> JsString("Some text"),
          "blocks"      -> JsArray(Seq.empty),
          "attachments" -> JsArray(Seq.empty)
        )

      val response =
        wsClient.url(s"$baseUrl/slack-notifications/v2/notification")
          .withHttpHeaders(
            "Authorization" -> "token",
            "Content-Type"  -> "application/json"
          ).post(payload).futureValue

      response.status shouldBe Helpers.ACCEPTED

      val msgId = (response.json \ "msgId").as[UUID]

      consumer.runQueue().futureValue

      eventually(timeout(Span(20, Seconds))) {
        val response =
          wsClient.url(s"$baseUrl/slack-notifications/v2/${msgId.toString}/status")
            .withHttpHeaders(
              "Authorization" -> "token"
            ).get().futureValue

        val status = (response.json \ "status").as[String]

        status shouldBe "complete"
      }

      queueRepo.getByMsgId(msgId).futureValue.map(_.status).forall(_ == ProcessingStatus.Succeeded) shouldBe true
    }

    "not queue message and return result straight away when error encountered during channel lookup" in {
      stubFor(
        get(urlEqualTo("/api/repositories/non-existent-repo"))
          .willReturn(aResponse().withStatus(404))
      )

      val queueSizeBefore = queueRepo.collection.countDocuments().toFuture().futureValue

      val payload =
        Json.obj(
          "displayName" -> JsString("my bot"),
          "emoji" -> JsString(":robot_face:"),
          "channelLookup" -> Json.obj(
            "by" -> JsString("github-repository"),
            "repositoryName" -> JsString("non-existent-repo")
          ),
          "text" -> JsString("Some text"),
          "blocks" -> JsArray(Seq.empty),
          "attachments" -> JsArray(Seq.empty)
        )

      val response =
        wsClient.url(s"$baseUrl/slack-notifications/v2/notification")
          .withHttpHeaders(
            "Authorization" -> "token",
            "Content-Type" -> "application/json"
          ).post(payload).futureValue

      response.status shouldBe Helpers.INTERNAL_SERVER_ERROR

      (response.json \ "errors" \ 0 \ "code").as[String] shouldBe "repository_not_found"

      val queueSizeAfter = queueRepo.collection.countDocuments().toFuture().futureValue

      // assert no work items created
      queueSizeBefore shouldBe queueSizeAfter
    }

    "stop processing in the event of a 429 response from Slack" in {
      val scenarioName = "Rate Limit"
      stubFor(
        post(urlEqualTo("/chat.postMessage"))
          .withHeader("Authorization", equalTo("Bearer token"))
          .inScenario(scenarioName)
          .whenScenarioStateIs(Scenario.STARTED)
          .willReturn(aResponse().withStatus(200).withBody("{}"))
      )

      stubFor(
        post(urlEqualTo("/chat.postMessage"))
          .withHeader("Authorization", equalTo("Bearer token"))
          .inScenario(scenarioName)
          .whenScenarioStateIs(Scenario.STARTED)
          .withRequestBody(containing("Test Message 2"))
          .willReturn(aResponse().withStatus(200).withBody("{}"))
          .willSetStateTo("LIMITED")
      )

      stubFor(
        post(urlEqualTo("/chat.postMessage"))
          .withHeader("Authorization", equalTo("Bearer token"))
          .inScenario(scenarioName)
          .whenScenarioStateIs("LIMITED")
          .willReturn(aResponse().withStatus(429).withBody("{}"))
      )

      val basePayload =
        Json.obj(
          "displayName" -> JsString("my bot"),
          "emoji" -> JsString(":robot_face:"),
          "channelLookup" -> Json.obj(
            "by" -> JsString("slack-channel"),
            "slackChannels" -> JsArray(Seq(JsString("a-channel")))
          ),
          "blocks" -> JsArray(Seq.empty),
          "attachments" -> JsArray(Seq.empty)
        )

      val msgIdMap: Map[Int, UUID] = (1 to 5).map { i =>
        val payload = basePayload + ("text" -> JsString(s"Test Message $i"))

        val response =
          wsClient.url(s"$baseUrl/slack-notifications/v2/notification")
            .withHttpHeaders(
              "Authorization" -> "token",
              "Content-Type" -> "application/json"
            ).post(payload).futureValue

        val msgId = (response.json \ "msgId").as[UUID]

        i -> msgId
      }.toMap

      consumer.runQueue().futureValue

      queueRepo.getByMsgId(msgIdMap(1)).futureValue.map(_.status) shouldBe Seq(ProcessingStatus.Succeeded)
      queueRepo.getByMsgId(msgIdMap(2)).futureValue.map(_.status) shouldBe Seq(ProcessingStatus.Succeeded)
      queueRepo.getByMsgId(msgIdMap(3)).futureValue.map(_.status) shouldBe Seq(ProcessingStatus.Failed) // Gets marked as failed in the service layer .recoverWith
      queueRepo.getByMsgId(msgIdMap(4)).futureValue.map(_.status) shouldBe Seq(ProcessingStatus.ToDo)
      queueRepo.getByMsgId(msgIdMap(5)).futureValue.map(_.status) shouldBe Seq(ProcessingStatus.ToDo)
    }
  }

}
