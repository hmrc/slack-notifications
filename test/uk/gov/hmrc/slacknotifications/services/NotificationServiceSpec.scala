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

import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.slacknotifications.connectors.SlackConnector
import uk.gov.hmrc.slacknotifications.model.SlackMessage

class NotificationServiceSpec extends WordSpec with Matchers with ScalaFutures with MockitoSugar with PropertyChecks {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "Sending notifications" should {
    "succeed if slack accepted the notification" in {
      val slackConnector = mock[SlackConnector]
      when(slackConnector.sendMessage(any())(any())).thenReturn(Future(HttpResponse(200)))

      val service = new NotificationService(slackConnector)
      val result  = service.sendMessage(SlackMessage("existentChannel")).futureValue

      result shouldBe Right(())
    }

    "return error if response was not 200" in {
      val invalidStatusCodes = Gen.choose(201, 599)
      forAll(invalidStatusCodes) { statusCode =>
        val slackConnector = mock[SlackConnector]
        val errorMsg       = "invalid_payload"
        when(slackConnector.sendMessage(any())(any()))
          .thenReturn(Future(HttpResponse(statusCode, responseString = Some(errorMsg))))

        val service = new NotificationService(slackConnector)
        val result  = service.sendMessage(SlackMessage("nonexistentChannel")).futureValue

        result shouldBe Left(NotificationService.Error(statusCode, errorMsg))
      }
    }
  }

}
