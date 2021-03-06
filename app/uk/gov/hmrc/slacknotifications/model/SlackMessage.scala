/*
 * Copyright 2021 HM Revenue & Customs
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

import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.slacknotifications.utils.AllowlistedLink

/**
  * More details: https://api.slack.com/docs/message-attachments
  */
case class Attachment(
  fallback: Option[String],
  color: Option[String],
  pretext: Option[String],
  author_name: Option[String],
  author_link: Option[String],
  author_icon: Option[String],
  title: Option[String],
  title_link: Option[String],
  text: Option[String],
  fields: Option[Seq[Attachment.Field]],
  image_url: Option[String],
  thumb_url: Option[String],
  footer: Option[String],
  footer_icon: Option[String],
  ts: Option[Int]
)

object Attachment {

  final case class Field(
    title: String,
    value: String,
    short: Boolean
  )

  object Field {
    implicit val format: OFormat[Field] = Json.format[Field]
  }

  implicit val format: OFormat[Attachment] = Json.format[Attachment]

  def sanitise(attch: Attachment): Attachment = attch.copy(
    fallback = attch.fallback.map{AllowlistedLink.sanitise(_)},
    color = attch.color.map{AllowlistedLink.sanitise(_)},
    pretext = attch.pretext.map{AllowlistedLink.sanitise(_)},
    author_name = attch.author_name.map{AllowlistedLink.sanitise(_)},
    author_link = attch.author_link.map{AllowlistedLink.sanitise(_)},
    author_icon = attch.author_icon.map{AllowlistedLink.sanitise(_)},
    title = attch.title.map{AllowlistedLink.sanitise(_)},
    title_link = attch.title_link.map{AllowlistedLink.sanitise(_)},
    text = attch.text.map{AllowlistedLink.sanitise(_)},
    image_url = attch.image_url.map{AllowlistedLink.sanitise(_)},
    thumb_url = attch.thumb_url.map{AllowlistedLink.sanitise(_)},
    footer = attch.footer.map{AllowlistedLink.sanitise(_)},
    footer_icon = attch.footer_icon.map{AllowlistedLink.sanitise(_)}
  )
}

case class SlackMessage(
  channel: String,
  text: String,
  username: String,
  icon_emoji: Option[String],
  attachments: Seq[Attachment]
)

object SlackMessage {
  implicit val format: OFormat[SlackMessage] = Json.format[SlackMessage]

  def sanitise(msg: SlackMessage): SlackMessage = msg.copy(
    text = AllowlistedLink.sanitise(msg.text),
    attachments = msg.attachments.map{ attachement: Attachment => Attachment.sanitise(attachement)}
  )
}
