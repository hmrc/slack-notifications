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

import cats.data.EitherT
import cats.instances.future._
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
)(using ExecutionContext
):

  def getExistingRepository(
    repoName: String
  )(using HeaderCarrier
  ): Future[Either[NotificationResult, RepositoryDetails]] =
    teamsAndReposConnector
      .getRepositoryDetails(repoName)
      .map:
        case Some(repoDetails) => Right(repoDetails)
        case None              => Left(NotificationResult().addError(Error.repositoryNotFound(repoName)))

  def getTeamsResponsibleForRepo(
    repoName         : String,
    repositoryDetails: RepositoryDetails
  ): Either[NotificationResult, List[String]] =
    repositoryDetails.owningTeams match
      case Nil   => Left(NotificationResult().addError(Error.teamsNotFoundForRepository(repoName)))
      case teams => Right(teams)

  def getExistingSlackChannel(
    teamName: String
  )(using HeaderCarrier
  ): Future[Either[(Seq[AdminSlackId], FallbackChannel), TeamChannel]] =
    EitherT.fromOptionF(
      userManagementConnector.getTeamSlackDetails(teamName).map(_.flatMap(extractSlackChannel)),
      ()
    ).leftSemiflatMap(_ =>
      userManagementConnector
        .getTeamUsers(teamName)
        .map: users =>
          ( users.filter(user => user.role == "team_admin" && user.slackId.isDefined)
              .map(user => AdminSlackId(user.slackId.get))
          , FallbackChannel(slackConfig.noTeamFoundAlert.channel)
          )
    ).value

  def extractSlackChannel(slackDetails: TeamDetails): Option[TeamChannel] =
    slackDetails.slackNotification.orElse(slackDetails.slack).flatMap: slackChannelUrl =>
      val urlWithoutTrailingSpace =
        if slackChannelUrl.endsWith("/") then
          slackChannelUrl.init
        else
          slackChannelUrl

      val slashPos = urlWithoutTrailingSpace.lastIndexOf("/")
      val s = urlWithoutTrailingSpace.substring(slashPos + 1)
      if slashPos > 0 && s.nonEmpty then Some(TeamChannel(s)) else None

case class AdminSlackId(asString: String) extends AnyVal
case class FallbackChannel(asString: String) extends AnyVal
case class TeamChannel    (asString: String) extends AnyVal
