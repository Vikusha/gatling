/*
 * Copyright 2011-2020 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.cache

import io.gatling.commons.util.Clock
import io.gatling.commons.util.NumberHelper._
import io.gatling.http.{ HeaderNames, HeaderValues }
import io.gatling.http.response.Response

import io.netty.handler.codec.DateFormatter
import io.netty.handler.codec.http.HttpHeaders

private[cache] trait ExpiresSupport {

  def clock: Clock

  private val MaxAgePrefix = "max-age="
  private val MaxAgeZero = MaxAgePrefix + "0"

  def extractMaxAgeValue(s: String): Option[Long] = {
    val index = s.indexOf(MaxAgePrefix)
    val start = MaxAgePrefix.length + index
    if (index >= 0 && start <= s.length)
      s.charAt(start) match {
        case '-'            => Some(-1)
        case c if c.isDigit => Some(extractLongValue(s, start))
        case _              => None
      } else
      None
  }

  def extractExpiresValue(timestring: String): Option[Long] = {

    def removeQuote(s: String) =
      if (s.isEmpty) {
        s
      } else {
        var start = 0
        var end = s.length

        if (s.charAt(0) == '"')
          start += 1

        if (s.charAt(s.length() - 1) == '"')
          end -= 1

        s.substring(start, end)
      }

    // FIXME use offset instead of 2 substrings
    val trimmedTimeString = removeQuote(timestring.trim)

    Option(DateFormatter.parseHttpDate(trimmedTimeString)).map(_.getTime)
  }

  private def cacheControlNoCache(cacheControlHeader: String): Boolean =
    cacheControlHeader.contains(HeaderValues.NoCache) || cacheControlHeader.contains(HeaderValues.NoStore) || cacheControlHeader.contains(MaxAgeZero)

  private def maxAgeAsExpiresValue(cacheControlHeader: String): Option[Long] =
    extractMaxAgeValue(cacheControlHeader).flatMap { maxAge =>
      if (maxAge < 0) {
        None
      } else {
        val updatedMaxAge = maxAge * 1000 + clock.nowMillis
        if (updatedMaxAge < 0) {
          None
        } else {
          Some(maxAge * 1000 + clock.nowMillis)
        }
      }
    }

  private def expiresValue(responseHeaders: HttpHeaders): Option[Long] = {
    val expiresHeader = responseHeaders.get(HeaderNames.Expires)
    if (expiresHeader != null) {
      extractExpiresValue(expiresHeader).filter(_ > clock.nowMillis)
    } else {
      None
    }
  }

  def getResponseExpires(response: Response): Option[Long] = {

    val responseHeaders = response.headers

    val pragmaHeader = responseHeaders.get(HeaderNames.Pragma)
    if (pragmaHeader != null && pragmaHeader.contains(HeaderValues.NoCache)) {
      None
    } else {
      val cacheControlHeader = responseHeaders.get(HeaderNames.CacheControl)
      if (cacheControlHeader != null && cacheControlNoCache(cacheControlHeader)) {
        None
      } else {
        // If a response includes both an Expires header and a max-age directive, the max-age directive overrides the Expires header,
        // even if the Expires header is more restrictive. (http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.3)
        if (cacheControlHeader != null) {
          maxAgeAsExpiresValue(cacheControlHeader)
        } else {
          expiresValue(responseHeaders)
        }
      }
    }
  }
}
