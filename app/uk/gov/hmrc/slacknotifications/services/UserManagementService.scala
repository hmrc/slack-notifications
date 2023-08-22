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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamName

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserManagementService @Inject()(
  userManagementConnector: UserManagementConnector
)(implicit
  ec: ExecutionContext
) {

  def getTeamsForLdapUser(ldapUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamName]] =
    userManagementConnector.getLdapUser(ldapUsername)
      .map(_.fold(List.empty[TeamName])(_.teamsAndRoles))

  def getTeamsForGithubUser(githubUsername: String)(implicit hc: HeaderCarrier): Future[List[TeamName]] =
    userManagementConnector.getGithubUser(githubUsername)
      .map(_.headOption)
      .map(_.fold(List.empty[TeamName])(_.teamsAndRoles))
}
