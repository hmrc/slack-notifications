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
import uk.gov.hmrc.slacknotifications.config.SlackConfig
import uk.gov.hmrc.slacknotifications.connectors.UserManagementConnector.TeamDetails
import uk.gov.hmrc.slacknotifications.connectors.{RepositoryDetails, TeamsAndRepositoriesConnector, UserManagementConnector}
import uk.gov.hmrc.slacknotifications.model._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ChannelLookupService @Inject()(
  slackConfig: SlackConfig,
  teamsAndReposConnector: TeamsAndRepositoriesConnector,
  userManagementConnector: UserManagementConnector
)(implicit
  ec: ExecutionContext
) {

  def getExistingRepository(
    repoName: String
  )(implicit
    hc: HeaderCarrier
  ): Future[Either[NotificationResult, RepositoryDetails]] =
    teamsAndReposConnector
      .getRepositoryDetails(repoName)
      .flatMap {
        case Some(repoDetails) => Future.successful(Right(repoDetails))
        case None => Future.successful(Left(NotificationResult().addError(Error.repositoryNotFound(repoName))))
      }

  private[services] def getTeamsResponsibleForRepo(repositoryDetails: RepositoryDetails): List[String] =
    if (repositoryDetails.owningTeams.nonEmpty)
      repositoryDetails.owningTeams
    else
      repositoryDetails.teamNames

  def getTeamsResponsibleForRepo(
    repoName: String,
    repositoryDetails: RepositoryDetails
  ): Future[Either[NotificationResult, List[String]]] =
    getTeamsResponsibleForRepo(repositoryDetails) match {
      case Nil => Future.successful(Left(NotificationResult().addError(Error.teamsNotFoundForRepository(repoName))))
      case teams => Future.successful(Right(teams))
    }

  def getExistingSlackChannel(
    teamName: String
  )(implicit
    hc: HeaderCarrier
  ): Future[Either[String, String]] =
    userManagementConnector
      .getTeamSlackDetails(teamName)
      .map(td => td.flatMap(extractSlackChannel))
      .flatMap {
        case Some(slackChannel) => Future.successful(Right(slackChannel))
        case None => Future.successful(Left(slackConfig.noTeamFoundAlert.channel))
      }

  def extractSlackChannel(slackDetails: TeamDetails): Option[String] =
    slackDetails.slackNotification.orElse(slackDetails.slack).flatMap { slackChannelUrl =>
      val urlWithoutTrailingSpace =
        if (slackChannelUrl.endsWith("/"))
          slackChannelUrl.init
        else
          slackChannelUrl

      val slashPos = urlWithoutTrailingSpace.lastIndexOf("/")
      val s = urlWithoutTrailingSpace.substring(slashPos + 1)
      if (slashPos > 0 && s.nonEmpty) Some(s) else None
    }

}
