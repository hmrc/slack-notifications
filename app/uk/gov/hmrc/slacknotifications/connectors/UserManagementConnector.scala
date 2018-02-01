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

import play.api.libs.json.{Format, JsValue, Json}
import play.api.{Configuration, Environment}
import scala.concurrent.Future
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

class UserManagementConnector(
  http: HttpClient,
  override val runModeConfiguration: Configuration,
  environment: Environment)
    extends ServicesConfig {

  import UserManagementConnector._

  val mode = environment.mode
  val url  = baseUrl("user-management")

  def getTeamSlackChannel(teamName: String)(implicit hc: HeaderCarrier): Future[Option[String]] =
    http.GET[HttpResponse](s"$url/v2/organisations/teams/$teamName").map(r => extractSlackChannel(r.json))

}

object UserManagementConnector {
  def extractSlackChannel(json: JsValue): Option[String] =
    for {
      js           <- Option(json)
      teamDetails  <- js.asOpt[TeamDetails]
      slackChannel <- teamDetails.slackChannel
    } yield slackChannel
}

case class TeamDetails(slack: String) {
  def slackChannel: Option[String] = {
    val s = slack.substring(slack.lastIndexOf("/") + 1)
    if (s.nonEmpty) Some(s) else None
  }
}

object TeamDetails {
  implicit val format: Format[TeamDetails] = Json.format[TeamDetails]
}
