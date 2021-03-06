/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.alpakka.s3.auth

import java.net.URLEncoder

import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}

import scala.annotation.tailrec

// Documentation: http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
private[alpakka] case class CanonicalRequest(method: String,
                                             uri: String,
                                             queryString: String,
                                             headerString: String,
                                             signedHeaders: String,
                                             hashedPayload: String) {
  def canonicalString: String = s"$method\n$uri\n$queryString\n$headerString\n\n$signedHeaders\n$hashedPayload"
}

private[alpakka] object CanonicalRequest {
  def from(req: HttpRequest): CanonicalRequest = {
    val hashedBody = req.headers.find(_.name == "x-amz-content-sha256").map(_.value).getOrElse("")
    CanonicalRequest(
      req.method.value,
      pathEncode(req.uri.path),
      canonicalQueryString(req.uri.query()),
      canonicalHeaderString(req.headers),
      signedHeadersString(req.headers),
      hashedBody
    )
  }

  def canonicalQueryString(query: Query): String =
    query.sortBy(_._1).map { case (a, b) => s"${uriEncode(a)}=${uriEncode(b)}" }.mkString("&")

  private def uriEncode(str: String) = URLEncoder.encode(str, "utf-8")

  def canonicalHeaderString(headers: Seq[HttpHeader]): String = {
    val grouped = headers.groupBy(_.lowercaseName())
    val combined = grouped.mapValues(_.map(_.value.replaceAll("\\s+", " ").trim).mkString(","))
    combined.toList.sortBy(_._1).map { case (k, v) => s"$k:$v" }.mkString("\n")
  }

  def signedHeadersString(headers: Seq[HttpHeader]): String =
    headers.map(_.lowercaseName()).distinct.sorted.mkString(";")

  private def pathEncode(path: Path): String = path match {
    case p if p.isEmpty => "/"
    case nonEmptyPath => pathEncodeRec(new StringBuilder, nonEmptyPath).toString()
  }

  @tailrec private def pathEncodeRec(builder: StringBuilder, path: Path): StringBuilder = path match {
    case Path.Empty ⇒ builder
    case Path.Slash(tail) ⇒ pathEncodeRec(builder += '/', tail)
    case Path.Segment(head, tail) ⇒
      pathEncodeRec(builder ++= uriEncodePath(head), tail)
  }

  private def toHexUtf8(ch: Char): String = "%" + Integer.toHexString(ch.toInt)

  // translated from java example at http://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
  private def uriEncodePath(input: String, encodeSlash: Boolean = true): String =
    input.flatMap {
      case '/' =>
        if (encodeSlash) "%2F"
        else "/"
      case ch
          if (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '~' || ch == '.' =>
        ch.toString
      case ch =>
        toHexUtf8(ch)
    }
}
