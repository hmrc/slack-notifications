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
import uk.gov.hmrc.slacknotifications.utils.LinkUtils

/**
  * More details: https://api.slack.com/docs/message-attachments
  */
case class Attachment(
  fallback   : Option[String] = None,
  color      : Option[String] = None,
  pretext    : Option[String] = None,
  author_name: Option[String] = None,
  author_link: Option[String] = None,
  author_icon: Option[String] = None,
  title      : Option[String] = None,
  title_link : Option[String] = None,
  text       : Option[String] = None,
  fields     : Option[Seq[Attachment.Field]] = None,
  image_url  : Option[String] = None,
  thumb_url  : Option[String] = None,
  footer     : Option[String] = None,
  footer_icon: Option[String] = None,
  ts         : Option[Int]    = None
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

  def sanitise(attch: Attachment, channel: String): Attachment =
    attch.copy(
      fallback    = attch.fallback.map{LinkUtils.updateLinks(_, channel)},
      color       = attch.color.map{LinkUtils.updateLinks(_, channel)},
      pretext     = attch.pretext.map{LinkUtils.updateLinks(_, channel)},
      author_name = attch.author_name.map{LinkUtils.updateLinks(_, channel)},
      author_link = attch.author_link.map{LinkUtils.updateLinks(_, channel)},
      author_icon = attch.author_icon.map{LinkUtils.updateLinks(_, channel)},
      title       = attch.title.map{LinkUtils.updateLinks(_, channel)},
      title_link  = attch.title_link.map{LinkUtils.updateLinks(_, channel)},
      text        = attch.text.map{LinkUtils.updateLinks(_, channel)},
      image_url   = attch.image_url.map{LinkUtils.updateLinks(_, channel)},
      thumb_url   = attch.thumb_url.map{LinkUtils.updateLinks(_, channel)},
      footer      = attch.footer.map{LinkUtils.updateLinks(_, channel)},
      footer_icon = attch.footer_icon.map{LinkUtils.updateLinks(_, channel)}
    )
}

case class LegacySlackMessage(
  channel             : String,
  text                : String,
  username            : String,
  icon_emoji          : Option[String],
  attachments         : Seq[Attachment],
  showAttachmentAuthor: Boolean
)

object LegacySlackMessage {
  implicit val format: OFormat[LegacySlackMessage] = Json.format[LegacySlackMessage]

  def sanitise(msg: LegacySlackMessage): LegacySlackMessage =
    msg.copy(
      text        = LinkUtils.updateLinks(msg.text, msg.channel),
      attachments = msg.attachments.map(Attachment.sanitise(_, msg.channel))
    )
}

// model for https://api.slack.com/methods/chat.postMessage
// omitting attachments and irrelevant optional fields
final case class SlackMessage(
  channel    : String
, text       : String
, blocks     : Seq[JsObject]
, username   : String
, emoji      : String
)

object SlackMessage {
  val format: Format[SlackMessage] =
    ( (__ \ "channel"    ).format[String]
    ~ (__ \ "text"       ).format[String]
    ~ (__ \ "blocks"     ).format[Seq[JsObject]]
    ~ (__ \ "username"   ).format[String]
    ~ (__ \ "icon_emoji" ).format[String]
    )(apply, unlift(unapply))

  def sanitise(msg: SlackMessage): SlackMessage = {
    val updatedText = LinkUtils.updateLinks(msg.text, msg.channel)

    // update links whilst preserving json structure
    def updateLinks(json: JsValue): JsValue =
      json match {
        case JsNull             => JsNull
        case boolean: JsBoolean => boolean
        case number : JsNumber  => number
        case string : JsString  => JsString(LinkUtils.updateLinks(string.value, msg.channel))
        case array  : JsArray   => JsArray(array.value.map(updateLinks))
        case obj    : JsObject  => JsObject(underlying = obj.value.map { case (k, v) => (k, updateLinks(v)) })
      }

    val updatedBlocks = msg.blocks.map(block => updateLinks(block).as[JsObject])

    msg.copy(
      text        = updatedText,
      blocks      = updatedBlocks
    )
  }

  def errorBlock(error: String): JsObject =
    JsObject(
      Map(
        "type" -> JsString("section"),
        "block_id" -> JsString("error_block"),
        "text" -> JsObject(
          Map(
            "type" -> JsString("mrkdwn"),
            "text" -> JsString(s"*Error:* $error")
          )
        )
      )
    )

  val divider: JsObject =
    JsObject(
      Map(
        "type" -> JsString("divider")
      )
    )
}
