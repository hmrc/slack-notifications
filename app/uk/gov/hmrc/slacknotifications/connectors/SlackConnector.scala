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
import play.api.{Configuration, Logging}
import play.api.libs.json.{Format, JsValue, Json}
import sttp.model.HeaderNames
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.slacknotifications.model.{Error, LegacySlackMessage, NotificationResult, SlackMessage}
import play.api.libs.ws.writeableOf_JsValue

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class SlackConnector @Inject()(
  httpClientV2 : HttpClientV2,
  configuration: Configuration
)(using ExecutionContext):
  import HttpReads.Implicits._

  private lazy val slackWebHookUri: String =
    configuration.get[String]("slack.webhookUrl")

  private lazy val slackApiUrl: String =
    configuration.get[String]("slack.apiUrl")

  private lazy val botToken: String =
    configuration.get[String]("slack.botToken")

  def sendMessage(message: LegacySlackMessage)(using HeaderCarrier): Future[HttpResponse] =
    httpClientV2
      .post(url"$slackWebHookUri")
      .withBody(Json.toJson(message))
      .withProxy
      .execute[HttpResponse]

  def postChatMessage(message: SlackMessage)(using HeaderCarrier): Future[Either[UpstreamErrorResponse, JsValue]] =
    given Format[SlackMessage] = SlackMessage.format

    httpClientV2
      .post(url"$slackApiUrl/chat.postMessage")
      .setHeader(HeaderNames.ContentType -> "application/json")
      .setHeader(HeaderNames.Authorization -> s"Bearer $botToken")
      .withBody(Json.toJson(message))
      .withProxy
      .execute[Either[UpstreamErrorResponse, JsValue]]


object SlackConnector extends Logging:
  def handleSlackExceptions(channel: String, teamName: Option[String]): PartialFunction[Throwable, Future[NotificationResult]] =
    case ex@UpstreamErrorResponse.WithStatusCode(404) if ex.message.contains("channel_not_found") =>
      handleChannelNotFound(channel)
    case UpstreamErrorResponse.Upstream4xxResponse(ex) =>
      Future.successful(logAndReturnSlackError(ex.statusCode, ex.message, channel, teamName))
    case UpstreamErrorResponse.Upstream5xxResponse(ex) =>
      Future.successful(logAndReturnSlackError(ex.statusCode, ex.message, channel, teamName))
    case NonFatal(ex) =>
      logger.error(s"Unable to notify Slack channel $channel", ex)
      Future.failed(ex)

  private def handleChannelNotFound(channel: String): Future[NotificationResult] =
    logger.error(Error.slackChannelNotFound(channel).message)
    Future.successful(NotificationResult().addError(Error.slackChannelNotFound(channel)))

  def logAndReturnSlackError(statusCode: Int, exceptionMessage: String, channel: String, teamName: Option[String]): NotificationResult =
    val slackError = Error.slackError(statusCode, exceptionMessage, channel, teamName)
    logger.error(s"Unable to notify Slack channel $channel, the following error occurred: ${slackError.message}")
    NotificationResult().addError(slackError)

final case class RateLimitExceededException() extends RuntimeException("Rate Limit Exceeded")
