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

import play.api.libs.json._

sealed trait Error extends Product with Serializable {
  def code: String

  def message: String

  override def toString: String = message
}

object Error {
  implicit val writes: Writes[Error] = Writes { error =>
    Json.obj(
      "code" -> error.code,
      "message" -> error.message
    )
  }
}

final case class SlackError(statusCode: Int, slackErrorMsg: String, channel: String, teamName: Option[String]) extends Error {
  val code = "slack_error"
  val message = teamName match {
    case Some(value) => s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel', team: '$value'"
    case None => s"Slack error, statusCode: $statusCode, msg: '$slackErrorMsg', channel: '$channel'"
  }
}

final case class RepositoryNotFound(repoName: String) extends Error {
  val code = "repository_not_found"
  val message = s"Repository: '$repoName' not found"
}

final case class TeamsNotFoundForRepository(repoName: String) extends Error {
  val code = "teams_not_found_for_repository"
  val message = s"Teams not found for repository: '$repoName'"
}

final case class TeamsNotFoundForUsername(userType: String, username: String) extends Error {
  val code = s"teams_not_found_for_${userType.toLowerCase}_username"
  val message = s"Teams not found for ${userType.capitalize} username: '$username'"
  val stylisedMessage = s"Teams not found for ${userType.capitalize} username: *$username*"
}

final case class SlackChannelNotFound(channelName: String) extends Error {
  val code = "slack_channel_not_found"
  val message = s"Slack channel: '$channelName' not found"
}

final case class UnableToFindTeamSlackChannelInUMP(teamName: String) extends Error {
  val code = "unable_to_find_team_slack_channel_in_ump"
  val message = s"Unable to deliver slack message to *$teamName*. Either the team does not exist in UMP, or it does not have a slack channel configured."
}

sealed trait Exclusion extends Product with Serializable {
  def code: String

  def message: String

  override def toString = message
}

object Exclusion {
  implicit val writes: Writes[Exclusion] = Writes { exclusion =>
    Json.obj(
      "code" -> exclusion.code,
      "message" -> exclusion.message
    )
  }
}

final case class NotARealTeam(name: String) extends Exclusion {
  val code = "not_a_real_team"
  val message = s"$name is not a real team"
}

final case class NotARealGithubUser(name: String) extends Exclusion {
  val code = "not_a_real_github_user"
  val message = s"$name is not a real Github user"
}

final case class NotificationDisabled(slackMessage: String) extends Exclusion {
  val code = "notification_disabled"
  val message = s"Slack notifications have been disabled. Slack message: $slackMessage"
}

final case class NotificationResult(
                                     successfullySentTo: Seq[String] = Nil,
                                     errors: Seq[Error] = Nil,
                                     exclusions: Seq[Exclusion] = Nil
                                   ) {
  def addError(e: Error*): NotificationResult =
    copy(errors = errors ++ e)

  def addSuccessfullySent(s: String*): NotificationResult =
    copy(successfullySentTo = successfullySentTo ++ s)

  def addExclusion(e: Exclusion*): NotificationResult =
    copy(exclusions = exclusions ++ e)
}

object NotificationResult {
  implicit val writes: OWrites[NotificationResult] = Json.writes[NotificationResult]
}
