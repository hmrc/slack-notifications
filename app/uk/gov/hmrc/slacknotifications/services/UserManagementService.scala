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

package uk.gov.hmrc.slacknotifications.services

import javax.inject.{Inject, Singleton}
import play.api.Logger
import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import play.api.cache._
import scala.util.Success
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamDetails, UmpUser}
import scala.concurrent.duration._

@Singleton
class UserManagementService @Inject()(connector: UserManagementConnector, cache: CacheApi) {

  val DUMMY_LDS_ADMIN_COMMITER = "n/a"

  def getTeamsForGithubUser(githubUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamDetails]] =
    if (githubUsername == DUMMY_LDS_ADMIN_COMMITER) {
      Future.successful(Nil)
    } else {
      for {
        maybeLdapUsername <- getLdapUsername(githubUsername)
        teams <- maybeLdapUsername match {
                  case Some(u) => connector.getTeamsForUser(u)
                  case None    => Future.successful(Nil)
                }
      } yield {
        Logger.info(s"Teams found for github username: '$githubUsername' are ${teams.mkString("[", ",", "]")}")
        teams
      }
    }

  def getLdapUsername(githubUsername: String)(implicit hc: HeaderCarrier): Future[Option[String]] = {
    val githubBaseUrl = "https://github.com"
    withCachedUmpUsers { users =>
      users
        .find { u =>
          u.github.contains(s"$githubBaseUrl/$githubUsername")
        }
        .flatMap(_.username)
    }
  }

  private def withCachedUmpUsers[A](f: List[UmpUser] => A)(implicit hc: HeaderCarrier): Future[A] = {
    val allUsers =
      cache
        .get[List[UmpUser]]("all-ump-users")
        .map(Future.successful)
        .getOrElse {
          connector.getAllUsers.andThen {
            case Success(umpUsers) => cache.set("all-ump-users", umpUsers, 15.minutes)
          }
        }
    allUsers.map(f)
  }

}
