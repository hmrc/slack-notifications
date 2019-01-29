/*
 * Copyright 2019 HM Revenue & Customs
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

import cats.data.NonEmptyList
import play.api.libs.json.Reads._
import play.api.libs.json.{Json, Reads, _}
import uk.gov.hmrc.slacknotifications.JsonHelpers

sealed trait ChannelLookup {
  def by: String
}

object ChannelLookup extends JsonHelpers {

  final case class GithubRepository(by: String, repositoryName: String) extends ChannelLookup

  final case class SlackChannel(by: String, slackChannels: NonEmptyList[String]) extends ChannelLookup

  final case class TeamsOfGithubUser(by: String, githubUsername: String) extends ChannelLookup

  private val githubRepositoryReads = Json.reads[GithubRepository].map(upcastAsChannelLookup)
  private val slackChannelReads = Json.reads[SlackChannel].map(upcastAsChannelLookup)
  private val teamsOfGithubUserReads = Json.reads[TeamsOfGithubUser].map(upcastAsChannelLookup)

  implicit val reads: Reads[ChannelLookup] =
    Reads[ChannelLookup] { json =>
      (json \ "by").validate[String].flatMap {
        case "github-repository" => json.validate(githubRepositoryReads)
        case "slack-channel" => json.validate(slackChannelReads)
        case "teams-of-github-user" => json.validate(teamsOfGithubUserReads)
        case _ => JsError("Unknown channel lookup type")
      }
    }

  private def upcastAsChannelLookup[A <: ChannelLookup](a: A): ChannelLookup = a: ChannelLookup

}
