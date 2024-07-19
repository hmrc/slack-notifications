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

import play.api.Logging
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserManagementConnector @Inject()(
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(implicit ec: ExecutionContext) extends Logging {

  import HttpReads.Implicits._
  import UserManagementConnector._

  private val baseUrl = servicesConfig.baseUrl("user-management")

  def getLdapUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[Option[User]] =
    httpClientV2
      .get(url"$baseUrl/user-management/users/$ldapUsername")
      .execute[Option[User]]

  def getGithubUser(githubUsername: String)(implicit hc: HeaderCarrier): Future[List[User]] =
    httpClientV2
      .get(url"$baseUrl/user-management/users?github=$githubUsername")
      .execute[List[User]]

  def getTeamUsers(team: String)(implicit hc: HeaderCarrier): Future[Seq[User]] =
    httpClientV2
      .get(url"$baseUrl/user-management/users?team=$team")
      .execute[Seq[User]]

  def getTeamSlackDetails(teamName: String)(implicit hc: HeaderCarrier): Future[Option[TeamDetails]] =
    httpClientV2
      .get(url"$baseUrl/user-management/teams/$teamName")
      .execute[Option[TeamDetails]]
}

object UserManagementConnector {

  case class User(
    ldapUsername  : String,
    slackId       : Option[String],
    githubUsername: Option[String],
    role          : String,
    teamNames     : List[TeamName]
  )

  object User {
    implicit val reads: Reads[User] = {
      implicit val tnr: Reads[TeamName] = TeamName.reads
      ( ( __ \ "username"      ).read[String]
      ~ ( __ \ "slackId"       ).readNullable[String]
      ~ ( __ \ "githubUsername").readNullable[String]
      ~ ( __ \ "role"          ).read[String]
      ~ ( __ \ "teamNames"     ).read[List[TeamName]]
      )(User.apply _)
    }
  }

  final case class TeamName(
    asString: String
  ) extends AnyVal

object TeamName {
  val reads: Reads[TeamName] =
    Reads.of[String].map(TeamName.apply)
}

  final case class TeamDetails(
    teamName         : String,
    slack            : Option[String],
    slackNotification: Option[String]
  )

  object TeamDetails {
    implicit val reads: Reads[TeamDetails] =
      ( (__ \ "teamName"         ).read[String]
      ~ (__ \ "slack"            ).readNullable[String]
      ~ (__ \ "slackNotification").readNullable[String]
      )(TeamDetails.apply _)
  }
}
