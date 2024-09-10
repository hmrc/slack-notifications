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
import play.api.libs.json.{Json, Reads, JsError, JsResult, JsValue}
import uk.gov.hmrc.slacknotifications.JsonHelpers

enum ChannelLookup:
  case GithubRepository(repositoryName: String)
  case Service(serviceName: String)
  case GithubTeam(teamName: String)
  case SlackChannel(slackChannels: NonEmptyList[String])
  case TeamsOfGithubUser(githubUsername: String)
  case TeamsOfLdapUser(ldapUsername: String)

object ChannelLookup extends JsonHelpers:

  private given Reads[ChannelLookup.GithubRepository] = Json.reads[ChannelLookup.GithubRepository]
  private given Reads[ChannelLookup.Service] = Json.reads[ChannelLookup.Service]
  private given Reads[ChannelLookup.GithubTeam] = Json.reads[ChannelLookup.GithubTeam]
  private given Reads[ChannelLookup.SlackChannel] = Json.reads[ChannelLookup.SlackChannel]
  private given Reads[ChannelLookup.TeamsOfGithubUser] = Json.reads[ChannelLookup.TeamsOfGithubUser]
  private given Reads[ChannelLookup.TeamsOfLdapUser] = Json.reads[ChannelLookup.TeamsOfLdapUser]

  given Reads[ChannelLookup] = Reads[ChannelLookup] { json =>
    (json \ "by").validate[String].flatMap:
      case "github-repository"    => json.validate[ChannelLookup.GithubRepository]
      case "service"              => json.validate[ChannelLookup.Service]
      case "github-team"          => json.validate[ChannelLookup.GithubTeam]
      case "slack-channel"        => json.validate[ChannelLookup.SlackChannel]
      case "teams-of-github-user" => json.validate[ChannelLookup.TeamsOfGithubUser]
      case "teams-of-ldap-user"   => json.validate[ChannelLookup.TeamsOfLdapUser]
      case _                      => JsError("Unknown channel lookup type")
  }
