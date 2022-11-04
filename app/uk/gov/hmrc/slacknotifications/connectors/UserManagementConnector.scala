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

import play.api.cache.AsyncCacheApi
import play.api.libs.functional.syntax.unlift

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.slacknotifications.config.UserManagementAuthConfig

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserManagementConnector @Inject()(httpClientV2  : HttpClientV2,
                                        servicesConfig: ServicesConfig,
                                        authConfig    : UserManagementAuthConfig,
                                        tokenCache    : AsyncCacheApi)(implicit ec: ExecutionContext) {

  import UserManagementConnector._
  import authConfig._
  import HttpReads.Implicits._

  private val url: String = {
    val keyInServices = "user-management.url"
    servicesConfig.getConfString(keyInServices, throw new RuntimeException(s"Could not find config $keyInServices"))
  }

  private lazy val loginUrl = {
    val keyInServices = "user-management.loginUrl"
    servicesConfig.getConfString(keyInServices, throw new RuntimeException(s"Could not find config $keyInServices"))
  }

  def login(): Future[UmpToken] = {
    implicit val lrf: OFormat[UmpLoginRequest] = UmpLoginRequest.format
    implicit val atf: OFormat[UmpAuthToken]    = UmpAuthToken.format
    implicit val hc: HeaderCarrier             = HeaderCarrier()

    val login = UmpLoginRequest(username, password)

    for {
      token <- httpClientV2.post(url"$loginUrl").withBody(Json.toJson(login)).execute[UmpAuthToken]
    } yield token
  }

  def retrieveToken() : Future[UmpToken] = {
    if (authEnabled)
      tokenCache.getOrElseUpdate[UmpToken]("token", tokenTTL)(login())
    else
      Future.successful(NoTokenRequired)
  }

  def getAllUsers(implicit hc: HeaderCarrier): Future[List[UmpUser]] = {
    for {
      token <- retrieveToken()
      resp  <- httpClientV2
                .get(url"$url/v2/organisations/users")
                .addHeaders(token.asHeaders():_*)
                .execute[UmpUsers]
                .map(_.users)
    } yield resp
  }

  def getTeamsForUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamDetails]] = {
    for {
      token <- retrieveToken()
      resp  <- httpClientV2
                .get(url"$url/v2/organisations/users/$ldapUsername/teams")
                .addHeaders(token.asHeaders():_*)
                .execute[Option[UmpTeams]]
                .map(_.map(_.teams).getOrElse(List.empty))
    } yield resp
  }

  def getTeamDetails(teamName: String)(implicit hc: HeaderCarrier): Future[Option[TeamDetails]] = {
    for {
      token <- retrieveToken()
      resp  <- httpClientV2
                .get(url"$url/v2/organisations/teams/$teamName")
                .addHeaders(token.asHeaders():_*)
                .execute[Option[TeamDetails]]
    } yield resp
  }
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


  sealed trait UmpToken {
    def asHeaders(): Seq[(String, String)]
  }

  case class UmpAuthToken(uid: String, token: String) extends UmpToken {
    def asHeaders(): Seq[(String, String)] = {
      Seq( "Token" -> token, "requester" -> uid)
    }
  }

  case object NoTokenRequired extends UmpToken {
    override def asHeaders(): Seq[(String, String)] = Seq.empty
  }

  object UmpAuthToken {
    val format: OFormat[UmpAuthToken] = (
      (__ \ "Token").format[String]
        ~ (__ \ "uid").format[String]
      )(UmpAuthToken.apply, unlift(UmpAuthToken.unapply))
  }

  case class UmpLoginRequest(username: String, password:String)

  object UmpLoginRequest {
    val format: OFormat[UmpLoginRequest] = (
      (__ \ "username").format[String]
        ~ (__ \ "password").format[String]
      )(UmpLoginRequest.apply, unlift(UmpLoginRequest.unapply))
  }
}
