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

package io.gatling.http.util

import java.net.URLDecoder
import java.nio.charset.{ Charset, StandardCharsets }

import scala.collection.{ breakOut, BitSet }
import scala.collection.JavaConverters._
import scala.io.Codec.UTF8
import scala.util.Try
import scala.util.control.NonFatal

import io.gatling.core.session._
import io.gatling.http.client.realm.{ BasicRealm, DigestRealm, Realm }
import io.gatling.http.client.uri.Uri
import io.gatling.http.{ HeaderNames, HeaderValues }

import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.{ HttpHeaders, HttpResponseStatus }
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.codec.http.cookie.{ ClientCookieDecoder, Cookie }

private[gatling] object HttpHelper extends StrictLogging {

  val HttpScheme = "http"
  val WsScheme = "ws"
  val OkCodes
      : BitSet = BitSet.empty + OK.code + NOT_MODIFIED.code + CREATED.code + ACCEPTED.code + NON_AUTHORITATIVE_INFORMATION.code + NO_CONTENT.code + RESET_CONTENT.code + PARTIAL_CONTENT.code + MULTI_STATUS.code + 208 + 209
  private val RedirectStatusCodes = BitSet.empty + MOVED_PERMANENTLY.code + FOUND.code + SEE_OTHER.code + TEMPORARY_REDIRECT.code + PERMANENT_REDIRECT.code

  def parseFormBody(body: String): List[(String, String)] = {
    def utf8Decode(s: String) = URLDecoder.decode(s, UTF8.name)

    body
      .split("&")
      .map(_.split("=", 2))
      .map { pair =>
        val paramName = utf8Decode(pair(0))
        val paramValue = if (pair.length > 1) utf8Decode(pair(1)) else ""
        paramName -> paramValue
      }(breakOut)
  }

  def buildBasicAuthRealm(username: Expression[String], password: Expression[String]): Expression[Realm] =
    (session: Session) =>
      for {
        usernameValue <- username(session)
        passwordValue <- password(session)
      } yield new BasicRealm(usernameValue, passwordValue)

  def buildDigestAuthRealm(username: Expression[String], password: Expression[String]): Expression[Realm] =
    (session: Session) =>
      for {
        usernameValue <- username(session)
        passwordValue <- password(session)
      } yield new DigestRealm(usernameValue, passwordValue)

  private def headerExists(headers: HttpHeaders, headerName: String, f: String => Boolean): Boolean = Option(headers.get(headerName)).exists(f)
  def isCss(headers: HttpHeaders): Boolean = headerExists(headers, HeaderNames.ContentType, _.startsWith(HeaderValues.TextCss))
  def isHtml(headers: HttpHeaders): Boolean =
    headerExists(headers, HeaderNames.ContentType, ct => ct.startsWith(HeaderValues.TextHtml) || ct.startsWith(HeaderValues.ApplicationXhtml))
  def isAjax(headers: HttpHeaders): Boolean = headerExists(headers, HeaderNames.XRequestedWith, _ == HeaderValues.XmlHttpRequest)

  private val ApplicationStart = "application/"
  private val ApplicationStartOffset = ApplicationStart.length
  private val ApplicationJavascriptEnd = HeaderValues.ApplicationJavascript.substring(ApplicationStartOffset)
  private val ApplicationJsonEnd = HeaderValues.ApplicationJson.substring(ApplicationStartOffset)
  private val ApplicationXmlEnd = HeaderValues.ApplicationXml.substring(ApplicationStartOffset)
  private val ApplicationFormUrlEncodedEnd = HeaderValues.ApplicationFormUrlEncoded.substring(ApplicationStartOffset)
  private val ApplicationXhtmlEnd = HeaderValues.ApplicationXhtml.substring(ApplicationStartOffset)
  private val TextStart = "text/"
  private val TextStartOffset = TextStart.length
  private val TextCssEnd = HeaderValues.TextCss.substring(TextStartOffset)
  private val TextCsvEnd = HeaderValues.TextCsv.substring(TextStartOffset)
  private val TextHtmlEnd = HeaderValues.TextHtml.substring(TextStartOffset)
  private val TextJavascriptEnd = HeaderValues.TextJavascript.substring(TextStartOffset)
  private val TextPlainEnd = HeaderValues.TextPlain.substring(TextStartOffset)
  private val TextXmlEnd = HeaderValues.TextXml.substring(TextStartOffset)

  def isText(headers: HttpHeaders): Boolean =
    headerExists(
      headers,
      HeaderNames.ContentType,
      ct =>
        ct.startsWith(ApplicationStart) && (
          ct.startsWith(ApplicationJavascriptEnd, ApplicationStartOffset)
            || ct.startsWith(ApplicationJsonEnd, ApplicationStartOffset)
            || ct.startsWith(ApplicationXmlEnd, ApplicationStartOffset)
            || ct.startsWith(ApplicationFormUrlEncodedEnd, ApplicationStartOffset)
            || ct.startsWith(ApplicationXhtmlEnd, ApplicationStartOffset)
        )
          || (ct.startsWith(TextStart) && (
            ct.startsWith(TextCssEnd, TextStartOffset)
              || ct.startsWith(TextCsvEnd, TextStartOffset)
              || ct.startsWith(TextHtmlEnd, TextStartOffset)
              || ct.startsWith(TextJavascriptEnd, TextStartOffset)
              || ct.startsWith(TextJavascriptEnd, TextStartOffset)
              || ct.startsWith(TextPlainEnd, TextStartOffset)
              || ct.startsWith(TextXmlEnd, TextStartOffset)
          ))
    )

  def resolveFromUri(rootURI: Uri, relative: String): Uri =
    if (relative.startsWith("//"))
      Uri.create(rootURI.getScheme + ":" + relative)
    else
      Uri.create(rootURI, relative)

  def resolveFromUriSilently(rootURI: Uri, relative: String): Option[Uri] =
    try {
      Some(resolveFromUri(rootURI, relative))
    } catch {
      case NonFatal(e) =>
        logger.info(s"Failed to resolve URI rootURI='$rootURI', relative='$relative'", e)
        None
    }

  def isOk(statusCode: Int): Boolean = OkCodes.contains(statusCode)
  def isRedirect(status: HttpResponseStatus): Boolean = RedirectStatusCodes.contains(status.code)
  def isPermanentRedirect(status: HttpResponseStatus): Boolean = status == MOVED_PERMANENTLY || status == PERMANENT_REDIRECT
  def isNotModified(status: HttpResponseStatus): Boolean = status == NOT_MODIFIED

  def isAbsoluteHttpUrl(url: String): Boolean = url.startsWith(HttpScheme)
  def isAbsoluteWsUrl(url: String): Boolean = url.startsWith(WsScheme)

  def extractCharsetFromContentType(contentType: String): Option[Charset] =
    contentType.indexOf("charset=") match {
      case -1 => None

      case s =>
        var start = s + "charset=".length

        if (contentType.regionMatches(true, start, "UTF-8", 0, 5)) {
          // minor optim, bypass lookup for most common
          Some(StandardCharsets.UTF_8)

        } else {
          var end = contentType.indexOf(';', start) match {
            case -1 => contentType.length

            case e => e
          }

          Try {
            while (contentType.charAt(start) == ' ' && start < end) start += 1

            while (contentType.charAt(end - 1) == ' ' && end > start) end -= 1

            if (contentType.charAt(start) == '"' && start < end)
              start += 1

            if (contentType.charAt(end - 1) == '"' && end > start)
              end -= 1

            val charsetString = contentType.substring(start, end)

            Charset.forName(charsetString)
          }.toOption
        }
    }

  def responseCookies(headers: HttpHeaders): List[Cookie] = {
    val setCookieValues = headers.getAll(HeaderNames.SetCookie)
    if (setCookieValues.isEmpty) {
      Nil
    } else {
      setCookieValues.asScala.flatMap(setCookie => Option(ClientCookieDecoder.LAX.decode(setCookie)).toList)(breakOut)
    }
  }
}
