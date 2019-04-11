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

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json.{Reads, _}

final case class MessageDetails(
                                 text: String,
                                 username: String,
                                 iconEmoji: Option[String] = None,
                                 attachments: Seq[Attachment] = Nil
                               ) {

  def getFields: Array[String] = getClass.getDeclaredFields.map(field => {
    field.setAccessible(true)
    field.getName -> field.get(this).toString
  }).filter(f => f._1 != "attachments").map(_._2)

}

object MessageDetails {
  implicit val reads: Reads[MessageDetails] = (
    (__ \ "text").read[String] and
      (__ \ "username").read[String] and
      (__ \ "iconEmoji").readNullable[String] and
      (__ \ "attachments").readNullable[Seq[Attachment]].map(_.getOrElse(Nil))
    ) (MessageDetails.apply _)
}

final case class NotificationRequest(
                                      channelLookup: ChannelLookup,
                                      messageDetails: MessageDetails
                                    )

object NotificationRequest {
  implicit val reads: Reads[NotificationRequest] = Json.reads[NotificationRequest]
}
