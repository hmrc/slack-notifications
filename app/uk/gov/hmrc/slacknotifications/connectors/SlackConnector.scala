/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.slacknotifications.model.SlackMessage

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackConnector @Inject()(http: HttpClient, configuration: Configuration)(implicit ec: ExecutionContext) {

  private val slackWebHookUri: String = {
    val key = "slack.webhookUrl"
    configuration
      .getOptional[String](key)
      .getOrElse(
        throw new RuntimeException(s"Missing required $key configuration")
      )
  }

  def sendMessage(message: SlackMessage)(implicit hc: HeaderCarrier): Future[HttpResponse] =
    http.POST[SlackMessage, HttpResponse](slackWebHookUri, message)

}
