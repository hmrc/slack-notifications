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

import play.api.Configuration
import uk.gov.hmrc.slacknotifications.config.DomainConfig
import uk.gov.hmrc.slacknotifications.base.UnitSpec

class SlackMessageSpec extends UnitSpec:
  "A legacy slack message" should:
    "contain a text message with allowlisted links" in new Fixtures:
      val message: LegacySlackMessage =
        LegacySlackMessage(
          channel  = "slack_channel",
          text     = "Random text and link to https://domain1/hmrc",
          username = "someone",
          None,
          Seq.empty,
          showAttachmentAuthor = true
        )
      val sanitisedMessage: LegacySlackMessage =
        LegacySlackMessage.sanitise(message, domainConfig)
      sanitisedMessage shouldBe message

    "contain a text with non-allowlisted links replaced" in new Fixtures:
      val message: LegacySlackMessage =
        LegacySlackMessage(
          channel  = "slack_channel",
          text     = "Evil text with links to http://very.bad.url/with-plenty-malware and http://url.i.dont?know=about",
          username = "someone",
          None,
          Seq.empty,
          showAttachmentAuthor = true
        )
      val sanitisedMessage: LegacySlackMessage =
        LegacySlackMessage.sanitise(message, domainConfig)

      val sanitisedText = s"Evil text with links to ${domainConfig.linkNotAllowListed} and ${domainConfig.linkNotAllowListed}"
      sanitisedMessage shouldBe message.copy(text = sanitisedText)

  "An attachment" should:
    val emptyAttachment: Attachment =
      Attachment(
        fallback    = None,
        color       = None,
        pretext     = None,
        author_name = None,
        author_link = None,
        author_icon = None,
        title       = None,
        title_link  = None,
        text        = None,
        fields      = None,
        image_url   = None,
        thumb_url   = None,
        footer      = None,
        footer_icon = None,
        ts          = None
      )

    "have sanitised links" in new Fixtures:
      val attachment: Attachment =
        emptyAttachment.copy(
          author_link = Some("https://domain1/hmrc"),
          title_link  = Some("http://very.bad.url/with-plenty-malware"),
          image_url   = Some("http://url.i.dont?know=about"),
          thumb_url   = Some("https://domain2/hmrc")
        )

      val expected: Attachment =
        emptyAttachment.copy(
          author_link = Some("https://domain1/hmrc"),
          title_link  = Some(domainConfig.linkNotAllowListed),
          image_url   = Some(domainConfig.linkNotAllowListed),
          thumb_url   = Some("https://domain2/hmrc")
        )

      Attachment.sanitise(attachment, "channel", domainConfig) shouldBe expected

  trait Fixtures:
    val domainConfig: DomainConfig =
      DomainConfig(Configuration(
        "allowed.domains"    ->  Seq("domain1", "domain2"),
        "linkNotAllowListed" -> "LINK NOT ALLOW LISTED"
    ))
