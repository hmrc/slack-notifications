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

package uk.gov.hmrc.slacknotifications.utils

import java.net.{URI, URL}
import scala.util.Try

object LinkUtils {
  val LinkNotAllowListed = "LINK NOT ALLOW LISTED"
  val allowedDomains: Set[String] = Set(
    "tax.service.gov.uk",
    "github.com",
    "pagerduty.com",
    "console.aws.amazon.com",
    "haveibeenpwned.com"
  )

  private val urlPattern = """(http[s]?.+?)(?="|`|\s|$)""".r

  val getUris: String => Set[URL] =
    str =>
      urlPattern.findAllMatchIn(str)
        .map( m => Try {new URI(m.group(1)).toURL})
        .flatMap(_.toOption)
        .toSet

  val isAllowListed: (String, Set[String]) => Boolean =
    (url, allowlist) =>
      allowlist.exists(url.contains(_))

  private def isCatalogueLink(host: String) =
    host.contains("catalogue.tax.service.gov.uk")

  private def hasSourceAttribute(url: String): Boolean =
    url.contains("source=")

  private def appendSource(link: String, channel: String): String = {
    val url    = new URI(link)
    val source = s"source=slack-$channel"
    new URI(
      url.getScheme,
      url.getAuthority,
      url.getPath,
      Option(url.getQuery).fold(source)(_ ++ s"&$source"),
      url.getFragment
    ).toString
  }

  private def updateCatalogueLinks(str: String, channel: String, links: List[URL]): String =
    links.foldLeft(str)((acc, link) => acc.replace(link.toString, appendSource(link.toString, channel)))

  private def overrideBadLinks(str: String, badLinkMessage: String, links: List[URL]): String =
    links.foldLeft(str)((acc, link) => acc.replace(link.toString, badLinkMessage))

  def updateLinks (text: String, channel: String): String = {
      val allLinks = getUris(text)
      val badLinks = allLinks
        .filterNot((x: URL) => isCatalogueLink(x.getHost))
        .filterNot((x: URL) => isAllowListed(x.getHost, allowedDomains))
        .toList
      val catalogueLinks: List[URL] = allLinks
        .filter((x: URL) => isCatalogueLink(x.getHost))
        .filterNot((x: URL) => hasSourceAttribute(x.toString))
        .toList
      updateCatalogueLinks(
        overrideBadLinks(text, LinkNotAllowListed, badLinks),
        channel,
        catalogueLinks)
    }
}
