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
import play.api.libs.json.{JsError, Json}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.{GithubRepository, GithubTeam, SlackChannel, TeamsOfGithubUser}
import uk.gov.hmrc.slacknotifications.base.UnitSpec

class ChannelLookupSpec extends UnitSpec:
  "Channel lookup" should:
    "be possible by github repository name" in:
      val by       = "github-repository"
      val repoName = "a-repo-name"
      val json =
        s"""
          {
            "by" : "$by",
            "repositoryName" : "$repoName"
          }
        """

      Json.parse(json).as[ChannelLookup] shouldBe GithubRepository(repoName)

    "be possible directly by slack channel name" in:
      val by           = "slack-channel"
      val slackChannel = "a-team-channel"
      val json =
        s"""
          {
            "by" : "$by",
            "slackChannels" : [
              "$slackChannel"
            ]
          }
        """

      val parsingResult = Json.parse(json).as[ChannelLookup]
      parsingResult shouldBe SlackChannel(NonEmptyList.of(slackChannel))

    "fail if user specified empty list of slack channels" in:
      val json =
        s"""
          {
            "by" : "slack-channel",
            "slackChannels" : []
          }
        """

      val parsingResult = Json.parse(json).validate[ChannelLookup]

      parsingResult shouldBe a[JsError]
      parsingResult.fold(
        invalid => invalid.head._2.head.message shouldBe "Expected a non-empty list",
        _ => fail("didn't expect parsing to succeed")
      )

    "be possible by github username for their teams" in:
      val by             = "teams-of-github-user"
      val githubUsername = "a-github-username"
      val json =
        s"""
          {
            "by" : "$by",
            "githubUsername" : "$githubUsername"
          }
        """

      val parsingResult = Json.parse(json).as[ChannelLookup]
      parsingResult shouldBe TeamsOfGithubUser(githubUsername)

    "be possible by github team" in:
      val by             = "github-team"
      val githubTeamName = "a-github-team"
      val json =
        s"""
          {
            "by" : "$by",
            "teamName" : "$githubTeamName"
          }
        """
      val parsingResult = Json.parse(json).as[ChannelLookup]
      parsingResult shouldBe GithubTeam(githubTeamName)

    "fail if lookup method not specified" in:
      val json =
        s"""
          {
            "by" : "sth-not-valid-here",
            "repositoryName" : "a-repo-name"
          }
        """

      val parsingResult = Json.parse(json).validate[ChannelLookup]

      parsingResult shouldBe a[JsError]
      parsingResult.fold(
        invalid => invalid.head._2.head.message shouldBe "Unknown channel lookup type",
        _ => fail("didn't expect parsing to succeed")
      )
