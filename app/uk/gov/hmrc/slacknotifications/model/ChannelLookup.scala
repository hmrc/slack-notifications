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

import cats.data.NonEmptyList
import play.api.libs.json.Reads._
import play.api.libs.json.{Json, Reads, _}
import uk.gov.hmrc.slacknotifications.JsonHelpers

sealed trait ChannelLookup

object ChannelLookup extends JsonHelpers {

  final case class GithubRepository(repositoryName: String) extends ChannelLookup

  final case class Service(serviceName: String) extends ChannelLookup

  final case class GithubTeam(teamName: String) extends ChannelLookup

  final case class SlackChannel(slackChannels: NonEmptyList[String]) extends ChannelLookup

  final case class TeamsOfGithubUser(githubUsername: String) extends ChannelLookup

  final case class TeamsOfLdapUser(ldapUsername: String) extends ChannelLookup

  private val githubRepositoryReads  = Json.reads[GithubRepository].map(upcastAsChannelLookup)
  private val serviceReads           = Json.reads[Service].map(upcastAsChannelLookup)
  private val githubTeamReads        = Json.reads[GithubTeam].map(upcastAsChannelLookup)
  private val slackChannelReads      = Json.reads[SlackChannel].map(upcastAsChannelLookup)
  private val teamsOfGithubUserReads = Json.reads[TeamsOfGithubUser].map(upcastAsChannelLookup)
  private val teamsOfLdapUserReads   = Json.reads[TeamsOfLdapUser].map(upcastAsChannelLookup)

  implicit val reads: Reads[ChannelLookup] =
    Reads[ChannelLookup] { json =>
      (json \ "by").validate[String].flatMap {
        case "github-repository"    => json.validate(githubRepositoryReads)
        case "service"              => json.validate(serviceReads)
        case "github-team"          => json.validate(githubTeamReads)
        case "slack-channel"        => json.validate(slackChannelReads)
        case "teams-of-github-user" => json.validate(teamsOfGithubUserReads)
        case "teams-of-ldap-user"   => json.validate(teamsOfLdapUserReads)
        case _                      => JsError("Unknown channel lookup type")
      }
    }

  private def upcastAsChannelLookup[A <: ChannelLookup](a: A): ChannelLookup = a: ChannelLookup

}
