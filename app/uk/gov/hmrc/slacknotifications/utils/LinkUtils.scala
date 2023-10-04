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

  // Captures urls terminated by:
  // - double quotes e.g. "https://example.com"
  // - a backtick e.g. `https://example.com`
  // - any whitespace e.g. https://example.com has lots of examples
  // - the end of the string e.g. visit https://example.com
  // - a literal pipe character e.g. Click <https://example.com|here>
  private val urlPattern = """(http[s]?.+?)(?="|`|\s|$|\|)""".r

  private[utils] def getUris(str: String): Set[URL] =
    urlPattern.findAllMatchIn(str)
      .map(m => Try(new URI(m.group(1)).toURL))
      .flatMap(_.toOption)
      .toSet

  private[utils] def isAllowListed(url: URL): Boolean =
    allowedDomains.exists(url.getHost.contains)

  private def isCatalogueLink(url: URL) =
    url.getHost.contains("catalogue.tax.service.gov.uk")

  private def appendSource(link: URL, source: String): URL = {
    val uri = link.toURI
    new URI(
      uri.getScheme,
      uri.getAuthority,
      uri.getPath,
      Option(uri.getQuery).fold(source)(_ ++ s"&$source"),
      uri.getFragment
    ).toURL
  }

  private def updateCatalogueLinks(channel: String, links: Set[URL])(str: String): String =
    links.foldLeft(str)((acc, link) =>
      if (Option(link.getQuery).exists(_.contains("source=")))
        acc
      else
        acc.replace(link.toString, appendSource(link, s"source=slack-$channel").toString)
    )

  private def overrideBadLinks(badLinkMessage: String, links: Set[URL])(str: String): String =
    links.foldLeft(str)((acc, link) => acc.replace(link.toString, badLinkMessage))

  def updateLinks(text: String, channel: String): String = {
    val (catalogueLinks, otherLinks) = getUris(text).partition(isCatalogueLink)
    val badLinks = otherLinks.filterNot(isAllowListed)
    updateCatalogueLinks(channel, catalogueLinks)(
      overrideBadLinks(LinkNotAllowListed, badLinks)(
        text
      )
    )
  }
}
