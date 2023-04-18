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

import uk.gov.hmrc.slacknotifications.test.UnitSpec
import uk.gov.hmrc.slacknotifications.utils.LinkUtils.LinkNotAllowListed

class SlackMessageSpec extends UnitSpec {
  "A slack message" should {
    "contain a text message with allowlisted links" in {
      val message = SlackMessage(
        channel  = "slack_channel",
        text     = "Random text and link to https://github.com/hmrc",
        username = "someone",
        None,
        Seq.empty,
        showAttachmentAuthor = true
      )
      val sanitisedMessage = SlackMessage.sanitise(message)
      sanitisedMessage shouldBe message
    }

    "contain a text with non-allowlisted links replaced" in {
      val message = SlackMessage(
        channel  = "slack_channel",
        text     = "Evil text with links to http://very.bad.url/with-plenty-malware and http://url.i.dont?know=about",
        username = "someone",
        None,
        Seq.empty,
        showAttachmentAuthor = true
      )
      val sanitisedMessage = SlackMessage.sanitise(message)

      val sanitisedText = s"Evil text with links to $LinkNotAllowListed and $LinkNotAllowListed"
      sanitisedMessage shouldBe message.copy(text = sanitisedText)
    }
  }

  "An attachment" should {
    val emptyAttachment = Attachment(
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

    "have sanitised links" in {
      val attachment = emptyAttachment.copy(
        author_link = Some("https://github.com/hmrc"),
        title_link  = Some("http://very.bad.url/with-plenty-malware"),
        image_url   = Some("http://url.i.dont?know=about"),
        thumb_url   = Some("https://github.com/hmrc")
      )

      val expected = emptyAttachment.copy(
        author_link = Some("https://github.com/hmrc"),
        title_link  = Some(LinkNotAllowListed),
        image_url   = Some(LinkNotAllowListed),
        thumb_url   = Some("https://github.com/hmrc")
      )

      Attachment.sanitise(attachment) shouldBe expected
    }
  }
}
