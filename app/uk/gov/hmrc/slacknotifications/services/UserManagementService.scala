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

package uk.gov.hmrc.slacknotifications.services

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.cache._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.{TeamDetails, UmpUser}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserManagementService @Inject()(
  connector: UserManagementConnector,
  cache    : AsyncCacheApi
)(implicit
  ec: ExecutionContext
) extends Logging {

  def getTeamsForGithubUser(githubUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamDetails]] =
    for {
      maybeLdapUsername <- getLdapUsername(githubUsername)
      teams             <- maybeLdapUsername match {
                            case Some(u) => connector.getTeamsForUser(u)
                            case None    => Future.successful(Nil)
                          }
      _                 =  logger.info(s"Teams found for github username: '$githubUsername' are ${teams.mkString("[", ",", "]")}")
    } yield teams

  def getLdapUsername(githubUsername: String)(implicit hc: HeaderCarrier): Future[Option[String]] = {
    val githubBaseUrl = "https://github.com"
    withCachedUmpUsers { users =>
      users
        .find { u =>
          u.github.exists(_.equalsIgnoreCase(s"$githubBaseUrl/$githubUsername"))
        }
        .flatMap(_.username)
    }
  }

  private def withCachedUmpUsers[A](f: List[UmpUser] => A)(implicit hc: HeaderCarrier): Future[A] =
    cache
      .getOrElseUpdate(key = "all-ump-users", expiration = 15.minutes)(orElse = connector.getAllUsers)
      .map(f)
}
