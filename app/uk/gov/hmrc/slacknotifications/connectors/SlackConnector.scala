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

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.{Format, JsValue, Json}
import sttp.model.HeaderNames
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.slacknotifications.model.SlackMessage
import play.api.libs.ws.writeableOf_JsValue

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackConnector @Inject()(
  httpClientV2 : HttpClientV2,
  configuration: Configuration
)(using ExecutionContext):
  import HttpReads.Implicits._

  private lazy val slackApiUrl: String =
    configuration.get[String]("slack.apiUrl")

  private lazy val botToken: String =
    configuration.get[String]("slack.botToken")

  def postChatMessage(message: SlackMessage)(using HeaderCarrier): Future[Either[SlackError, JsValue]] =
    given Format[SlackMessage] = SlackMessage.format

    httpClientV2
      .post(url"$slackApiUrl/chat.postMessage")
      .setHeader(HeaderNames.ContentType -> "application/json")
      .setHeader(HeaderNames.Authorization -> s"Bearer $botToken")
      .withBody(Json.toJson(message))
      .withProxy
      .execute[JsValue]
      .map:
        jsValue => // https://api.slack.com/methods/chat.postMessage#errors
          (jsValue \ "ok").asOpt[Boolean] match
            case Some(false) =>
              val code   = (jsValue \ "error").asOpt[String].getOrElse("unknown_error")
              val errors = (jsValue \ "errors").asOpt[Seq[String]].getOrElse(Seq.empty)
              Left(SlackError(code, errors))
            case _ => Right(jsValue)

case class RateLimitExceededException() extends RuntimeException("Rate Limit Exceeded")

case class SlackError(val code: String, val errors: Seq[String])
