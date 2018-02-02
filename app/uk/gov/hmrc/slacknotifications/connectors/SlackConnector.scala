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

package uk.gov.hmrc.slacknotifications.connectors

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import scala.concurrent.Future
import scala.util.control.NonFatal
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import uk.gov.hmrc.slacknotifications.model.SlackMessage

@Singleton
class SlackConnector @Inject()(http: HttpClient, configuration: Configuration) {

  val slackWebHookUri = {
    val key = "slack.webhookUrl"
    configuration
      .getString(key)
      .getOrElse(
        throw new RuntimeException(s"Missing required $key configuration")
      )
  }

  def sendMessage(message: SlackMessage)(implicit hc: HeaderCarrier): Future[HttpResponse] =
    try {
      http.POST[SlackMessage, HttpResponse](slackWebHookUri, message)
    } catch {
      case NonFatal(ex) =>
        Logger.error(s"Unable to notify ${message.channel} on Slack", ex)
        Future.failed(ex)
    }

}
