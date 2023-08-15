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
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName.teamNameReads

import java.net.URL
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

  private def getUser(url: URL, userType: String, username: String)(implicit hc: HeaderCarrier): Future[List[TeamName]] = {
    httpClientV2
      .get(url)
      .execute[Option[List[TeamName]]]
      .map {
        case Some(teams) => teams
        case _           => logger.info(s"No user details found for $userType username: $username")
                            List.empty[TeamName]
      }
  }

  def getTeamsForLdapUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamName]] =
    getUser(url"$baseUrl/users/$ldapUsername", "ldap", ldapUsername)

  def getTeamsForGithubUser(githubUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamName]] =
    getUser(url"$baseUrl/users?github=$githubUsername", "github", githubUsername)

  def getTeamSlackDetails(teamName: String)(implicit hc: HeaderCarrier): Future[Option[TeamDetails]] =
    httpClientV2
      .get(url"$baseUrl/teams/$teamName")
      .execute[Option[TeamDetails]]
}

object UserManagementConnector {

  final case class TeamName(
    asString: String
  ) extends AnyVal

  object TeamName {
    implicit val teamNameReads: Reads[List[TeamName]] =
      (__ \ "teamsAndRoles").read(Reads.seq((__ \ "teamName").read[String])).map(_.toList.map(TeamName.apply))
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
