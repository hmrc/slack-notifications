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

import play.api.libs.functional.syntax.{toFunctionalBuilderOps, unlift}
import play.api.libs.json._

final case class Error(code: String, message: String) {
  override def toString: String = message
}

object Error {
  val format: Format[Error] =
    ( (__ \ "code"   ).format[String]
    ~ (__ \ "message").format[String]
    )(apply, unlift(unapply))

  def slackError(
    statusCode   : Int,
    slackErrorMsg: String,
    channel      : String,
    teamName     : Option[String]
  ): Error = {
    val code = "slack_error"
    val message = teamName match {
      case Some(value) => s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel', team: '$value'"
      case None        => s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel'"
    }

    Error(code, message)
  }

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

  def slackChannelNotFound(channelName: String): Error =
    Error(
      code    = "slack_channel_not_found",
      message = s"Slack channel: '$channelName' not found"
    )

  def unableToFindTeamSlackChannelInUMP(teamName: String): Error =
    Error(
      code    = "unable_to_find_team_slack_channel_in_ump",
      message = s"Unable to deliver slack message to *$teamName*. Either the team does not exist in UMP, or it does not have a slack channel configured."
    )

  def unsupportedChannelLookUp(lookup: String): Error =
    Error(
      code    = "unsupported_channel_look_up",
      message = s"channel look up by: '$lookup' is not supported. Please use the v2 API."
    )
}

final case class Exclusion(code: String, message: String) {
  override def toString: String = message
}

object Exclusion {
  val format: Format[Exclusion] =
    ( (__ \ "code"   ).format[String]
    ~ (__ \ "message").format[String]
    )(apply, unlift(unapply))

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
}

final case class NotificationResult(
  successfullySentTo: Seq[String]    = Nil,
  errors            : Seq[Error]     = Nil,
  exclusions        : Seq[Exclusion] = Nil
) {
  def addError(e: Error*): NotificationResult =
    copy(errors = (errors ++ e).distinct)

  def addSuccessfullySent(s: String*): NotificationResult =
    copy(successfullySentTo = (successfullySentTo ++ s).distinct)

  def addExclusion(e: Exclusion*): NotificationResult =
    copy(exclusions = (exclusions ++ e).distinct)
}

object NotificationResult {
  implicit val errorFormat: Format[Error]     = Error.format
  implicit val excluFormat: Format[Exclusion] = Exclusion.format

  val format: Format[NotificationResult] =
    ( (__ \ "successfullySentTo").format[Seq[String]]
    ~ (__ \ "errors"            ).format[Seq[Error]]
    ~ (__ \ "exclusions"        ).format[Seq[Exclusion]]
    )(apply, unlift(unapply))

  def concatResults(results: Seq[NotificationResult]): NotificationResult =
    results.foldLeft(NotificationResult())((acc, current) =>
      acc
        .addSuccessfullySent(current.successfullySentTo: _*)
        .addError(current.errors: _*)
        .addExclusion(current.exclusions: _*)
    )
}
