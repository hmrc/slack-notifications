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

package uk.gov.hmrc.slacknotifications.model

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._

case class Error(code: String, message: String):
  override def toString: String = message

object Error:
  private val umpTeamsUrl = "https://user-management.tools.tax.service.gov.uk/teams"

  val format: Format[Error] =
    ( (__ \ "code"   ).format[String]
    ~ (__ \ "message").format[String]
    )(apply, e => Tuple.fromProductTyped(e))

  def slackError(
    statusCode   : Int,
    slackErrorMsg: String,
    channel      : String,
    teamName     : Option[String]
  ): Error =
    val code = "slack_error"
    val message = teamName match
      case Some(value) => s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel', team: '$value'"
      case None        => s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel'"

    Error(code, message)

  def repositoryNotFound(repoName: String): Error =
    Error(
      code    = "repository_not_found",
      message = s"Repository: '$repoName' not found"
    )

  def teamsNotFoundForRepository(repoName: String): Error =
    Error(
      code    = "teams_not_found_for_repository",
      message = s"Teams not found for repository: '$repoName'"
    )

  def teamsNotFoundForUsername(userType: String, username: String): Error =
    Error(
      code    = s"teams_not_found_for_${userType.toLowerCase}_username",
      message = s"Teams not found for ${userType.capitalize} username: *$username*"
    )

  def slackChannelNotFound(channelName: String, channelLookup: Option[ChannelLookup] = None): Error =
    Error(
      code    = "slack_channel_not_found",
      message = s"Slack channel: '$channelName' not found${channelLookup.fold("")(cl => s" using channelLookup: ${Json.toJson(cl)}")}"
    )

  def unableToFindTeamSlackChannelInUMP(teamName: String, numAdmins: Int): Error =
    Error(
      code = "unable_to_find_team_slack_channel_in_ump",
      message = s"Unable to deliver slack message to team <$umpTeamsUrl/$teamName|*$teamName*>. Either the team does not exist in UMP, or it does not have a slack channel configured. *$numAdmins* admins have been notified."
    )

  def unableToFindTeamSlackChannelInUMPandNoSlackAdmins(teamName: String): Error =
    Error(
      code = "unable_to_find_team_slack_channel_in_ump_and_no_slack_admins",
      message = s"Unable to deliver slack message to team <$umpTeamsUrl/$teamName|*$teamName*>. Either the team does not exist in UMP, or it does not have a slack channel configured.\n"
        + s"Could not find any admins to fallback and deliver message to for team <$umpTeamsUrl/$teamName|*$teamName*> with missing channel. Either team has no admins, or admins do not have slack setup."
    )

  def errorForAdminMissingTeamSlackChannel(teamName: String): Error =
    Error(
      code    = "unable_to_find_team_slack_channel_in_ump_admin",
      message = s"Unable to deliver slack message to team <$umpTeamsUrl/$teamName|*$teamName*>. The team does not have a slack channel configured.\n" +
        "You are receiving this alert since you are an *admin* in this team. Please configure a team slack notification channel."
    )

case class Exclusion(code: String, message: String):
  override def toString: String = message

object Exclusion:
  val format: Format[Exclusion] =
    ( (__ \ "code"   ).format[String]
    ~ (__ \ "message").format[String]
    )(apply, e => Tuple.fromProductTyped(e))

  def notARealTeam(name: String): Exclusion =
    Exclusion(
      code    = "not_a_real_team",
      message = s"$name is not a real team"
    )

  def notARealGithubUser(name: String): Exclusion =
    Exclusion(
      code    = "not_a_real_github_user",
      message = s"$name is not a real Github user"
    )

  def notificationDisabled(slackMessage: String): Exclusion =
    Exclusion(
      code    = "notification_disabled",
      message = s"Slack notifications have been disabled. Slack message: $slackMessage"
    )

case class NotificationResult(
  successfullySentTo: Seq[String]    = Nil,
  errors            : Seq[Error]     = Nil,
  exclusions        : Seq[Exclusion] = Nil
):
  def addError(e: Error*): NotificationResult =
    copy(errors = (errors ++ e).distinct)

  def addSuccessfullySent(s: String*): NotificationResult =
    copy(successfullySentTo = (successfullySentTo ++ s).distinct)

  def addExclusion(e: Exclusion*): NotificationResult =
    copy(exclusions = (exclusions ++ e).distinct)

object NotificationResult:
  val format: Format[NotificationResult] =
    given Format[Error]     = Error.format
    given Format[Exclusion] = Exclusion.format
    ( (__ \ "successfullySentTo").format[Seq[String]]
    ~ (__ \ "errors"            ).format[Seq[Error]]
    ~ (__ \ "exclusions"        ).format[Seq[Exclusion]]
    )(apply, n => Tuple.fromProductTyped(n))

  def concatResults(results: Seq[NotificationResult]): NotificationResult =
    results.foldLeft(NotificationResult())((acc, current) =>
      acc
        .addSuccessfullySent(current.successfullySentTo: _*)
        .addError(current.errors: _*)
        .addExclusion(current.exclusions: _*)
    )
