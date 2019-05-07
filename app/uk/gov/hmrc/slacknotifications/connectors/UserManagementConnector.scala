/*
 * Copyright 2019 HM Revenue & Customs
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
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.config.ServicesConfig
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserManagementConnector @Inject()(
  http: HttpClient,
  override val runModeConfiguration: Configuration,
  environment: Environment)(implicit ec: ExecutionContext)
    extends ServicesConfig {

  val mode = environment.mode

  import UserManagementConnector._

  val url: String = {
    val keyInServices = "user-management.url"
    getConfString(keyInServices, throw new RuntimeException(s"Could not find config $keyInServices"))
  }

  def getAllUsers(implicit hc: HeaderCarrier): Future[List[UmpUser]] =
    http.GET[UmpUsers](s"$url/v2/organisations/users").map(_.users)

  def getTeamsForUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamDetails]] =
    http
      .GET[Option[UmpTeams]](s"$url/v2/organisations/users/$ldapUsername/teams")
      .map(_.map(_.teams).getOrElse(List.empty))

  def getTeamDetails(teamName: String)(implicit hc: HeaderCarrier): Future[Option[TeamDetails]] =
    http.GET[Option[TeamDetails]](s"$url/v2/organisations/teams/$teamName")

}

object UserManagementConnector {

  final case class UmpUsers(users: List[UmpUser])

  object UmpUsers {
    implicit val format: Format[UmpUsers] = Json.format[UmpUsers]
  }

  final case class UmpUser(
    github: Option[String],
    username: Option[String]
  )

  object UmpUser {
    implicit val format: Format[UmpUser] = Json.format[UmpUser]
  }

  final case class UmpTeams(teams: List[TeamDetails])

  object UmpTeams {
    implicit val format: Format[UmpTeams] = Json.format[UmpTeams]
  }

  final case class TeamDetails(
    slack: Option[String],
    slackNotification: Option[String],
    team: String
  )

  object TeamDetails {
    implicit val format: Format[TeamDetails] = Json.format[TeamDetails]
  }

}
