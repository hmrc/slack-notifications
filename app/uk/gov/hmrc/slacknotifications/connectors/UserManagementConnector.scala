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
import play.api.libs.json.{Format, Json}
import play.api.{Configuration, Environment}
import scala.concurrent.Future
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

@Singleton
class UserManagementConnector @Inject()(
  http: HttpClient,
  override val runModeConfiguration: Configuration,
  environment: Environment)
    extends ServicesConfig {

  val mode = environment.mode

  import UserManagementConnector._

  val url: String = {
    val keyInServices = "user-management.url"
    getConfString(keyInServices, throw new RuntimeException(s"Could not find config $keyInServices"))
  }

  def getAllUsers(implicit hc: HeaderCarrier): Future[List[UmpUser]] =
    http
      .GET[HttpResponse](s"$url/v2/organisations/users")
      .map { resp =>
        (for {
          json  <- Option(resp.json)
          users <- (json \ "users").asOpt[List[UmpUser]]
        } yield users).getOrElse(Nil)
      }

  def getTeamsForUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamDetails]] =
    http.GET[HttpResponse](s"$url/v2/organisations/users/$ldapUsername/teams").map { resp =>
      val maybeTeams =
        for {
          json  <- Option(resp.json)
          teams <- (json \ "teams").asOpt[List[TeamDetails]]
        } yield teams
      maybeTeams.getOrElse(Nil)
    }

  def getTeamDetails(teamName: String)(implicit hc: HeaderCarrier): Future[Option[TeamDetails]] =
    http.GET[HttpResponse](s"$url/v2/organisations/teams/$teamName").map { resp =>
      for {
        json        <- Option(resp.json)
        teamDetails <- json.asOpt[TeamDetails]
      } yield teamDetails
    }

}

object UserManagementConnector {

  final case class UmpUser(
    github: Option[String],
    username: Option[String]
  )

  object UmpUser {
    implicit val format: Format[UmpUser] = Json.format[UmpUser]
  }

  final case class TeamDetails(
    slack: Option[String],
    team: String
  )

  object TeamDetails {
    implicit val format: Format[TeamDetails] = Json.format[TeamDetails]
  }

}
