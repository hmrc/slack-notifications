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

package uk.gov.hmrc.slacknotifications.connectors

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserManagementConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(implicit ec: ExecutionContext) {
  import UserManagementConnector._
  import HttpReads.Implicits._

  private val url: String = {
    val keyInServices = "user-management.url"
    servicesConfig.getConfString(keyInServices, throw new RuntimeException(s"Could not find config $keyInServices"))
  }

  def getAllUsers(implicit hc: HeaderCarrier): Future[List[UmpUser]] =
    httpClientV2
      .get(url"$url/v2/organisations/users")
      .execute[UmpUsers]
      .map(_.users)

  def getTeamsForUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamDetails]] =
    httpClientV2
      .get(url"$url/v2/organisations/users/$ldapUsername/teams")
      .execute[Option[UmpTeams]]
      .map(_.map(_.teams).getOrElse(List.empty))

  def getTeamDetails(teamName: String)(implicit hc: HeaderCarrier): Future[Option[TeamDetails]] =
    httpClientV2
      .get(url"$url/v2/organisations/teams/$teamName")
      .execute[Option[TeamDetails]]
}

object UserManagementConnector {

  final case class UmpUsers(
    users: List[UmpUser]
  )

  object UmpUsers {
    implicit val format: Format[UmpUsers] = Json.format[UmpUsers]
  }

  final case class UmpUser(
    github  : Option[String],
    username: Option[String]
  )

  object UmpUser {
    implicit val format: Format[UmpUser] = Json.format[UmpUser]
  }

  final case class UmpTeams(
    teams: List[TeamDetails]
  )

  object UmpTeams {
    implicit val format: Format[UmpTeams] = Json.format[UmpTeams]
  }

  final case class TeamDetails(
    slack            : Option[String],
    slackNotification: Option[String],
    team             : String
  )

  object TeamDetails {
    implicit val format: Format[TeamDetails] = Json.format[TeamDetails]
  }
}
