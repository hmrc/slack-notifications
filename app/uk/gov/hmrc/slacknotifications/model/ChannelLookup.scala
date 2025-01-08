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
import play.api.libs.json._
import uk.gov.hmrc.slacknotifications.JsonHelpers

enum ChannelLookup:
  case GithubRepository(repositoryName: String)
  case Service(serviceName: String)
  case GithubTeam(teamName: String)
  case SlackChannel(slackChannels: NonEmptyList[String])
  case TeamsOfGithubUser(githubUsername: String)
  case TeamsOfLdapUser(ldapUsername: String)

object ChannelLookup extends JsonHelpers:

  private given Format[ChannelLookup.GithubRepository]  = Json.format[ChannelLookup.GithubRepository]
  private given Format[ChannelLookup.Service]           = Json.format[ChannelLookup.Service]
  private given Format[ChannelLookup.GithubTeam]        = Json.format[ChannelLookup.GithubTeam]
  private given Format[ChannelLookup.SlackChannel]      = Json.format[ChannelLookup.SlackChannel]
  private given Format[ChannelLookup.TeamsOfGithubUser] = Json.format[ChannelLookup.TeamsOfGithubUser]
  private given Format[ChannelLookup.TeamsOfLdapUser]   = Json.format[ChannelLookup.TeamsOfLdapUser]

  given Format[ChannelLookup] = Format[ChannelLookup](
    Reads[ChannelLookup] { json =>
      (json \ "by").validate[String].flatMap {
        case "github-repository"    => json.validate[ChannelLookup.GithubRepository]
        case "service"              => json.validate[ChannelLookup.Service]
        case "github-team"          => json.validate[ChannelLookup.GithubTeam]
        case "slack-channel"        => json.validate[ChannelLookup.SlackChannel]
        case "teams-of-github-user" => json.validate[ChannelLookup.TeamsOfGithubUser]
        case "teams-of-ldap-user"   => json.validate[ChannelLookup.TeamsOfLdapUser]
        case _                      => JsError("Unknown channel lookup type")
      }
    },
    Writes[ChannelLookup] {
      case lookup: ChannelLookup.GithubRepository =>
        Json.toJson(lookup).as[JsObject] + ("by" -> JsString("github-repository"))
      case lookup: ChannelLookup.Service =>
        Json.toJson(lookup).as[JsObject] + ("by" -> JsString("service"))
      case lookup: ChannelLookup.GithubTeam =>
        Json.toJson(lookup).as[JsObject] + ("by" -> JsString("github-team"))
      case lookup: ChannelLookup.SlackChannel =>
        Json.toJson(lookup).as[JsObject] + ("by" -> JsString("slack-channel"))
      case lookup: ChannelLookup.TeamsOfGithubUser =>
        Json.toJson(lookup).as[JsObject] + ("by" -> JsString("teams-of-github-user"))
      case lookup: ChannelLookup.TeamsOfLdapUser =>
        Json.toJson(lookup).as[JsObject] + ("by" -> JsString("teams-of-ldap-user"))
    }
  )
