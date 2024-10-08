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

import play.api.libs.functional.syntax._
import play.api.libs.json.{Reads, __}

case class MessageDetails(
  text                : String,
  attachments         : Seq[Attachment] = Nil,
  showAttachmentAuthor: Boolean = true
)

object MessageDetails:
  given Reads[MessageDetails] =
    ( (__ \ "text"                ).read[String]
    ~ (__ \ "attachments"         ).readNullable[Seq[Attachment]].map(_.getOrElse(Nil))
    ~ (__ \ "showAttachmentAuthor").readNullable[Boolean].map(_.getOrElse(true))
    )(MessageDetails.apply _)

case class NotificationRequest(
  channelLookup : ChannelLookup,
  messageDetails: MessageDetails
)

object NotificationRequest:
  given Reads[NotificationRequest] =
    ( (__ \ "channelLookup" ).read[ChannelLookup]
    ~ (__ \ "messageDetails").read[MessageDetails]
    )(NotificationRequest.apply _)
