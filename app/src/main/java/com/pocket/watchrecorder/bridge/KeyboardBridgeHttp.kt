package com.pocket.watchrecorder.bridge

import java.net.URLDecoder

/**
 * The HTTP bits of [KeyboardBridge], kept separate and pure so they can be
 * unit tested. Hand-rolled because the whole point of this feature is to avoid
 * pulling a web server, or a companion app, onto a watch.
 *
 * This speaks only enough HTTP/1.1 to serve one page to one phone browser on
 * the local network. It is not a general server and should never be exposed
 * beyond that.
 */

internal data class BridgeRequest(
    val method: String,
    val path: String,
    val body: String
)

/** Parses an `application/x-www-form-urlencoded` body. */
internal fun parseFormBody(body: String): Map<String, String> =
    body.split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val index = pair.indexOf('=')
            val rawName = if (index < 0) pair else pair.substring(0, index)
            val rawValue = if (index < 0) "" else pair.substring(index + 1)
            runCatching {
                URLDecoder.decode(rawName, "UTF-8") to URLDecoder.decode(rawValue, "UTF-8")
            }.getOrNull()
        }
        .toMap()

/** Parses the request line of "GET /path HTTP/1.1". */
internal fun parseRequestLine(line: String): Pair<String, String>? {
    val parts = line.trim().split(' ')
    if (parts.size < 2) return null
    return parts[0].uppercase() to parts[1]
}

/** Reads the Content-Length header out of already-read header lines. */
internal fun contentLengthOf(headers: List<String>): Int =
    headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()
        ?.coerceIn(0, MAX_BODY_BYTES)
        ?: 0

/** Anything larger than this is not someone typing on a phone. */
internal const val MAX_BODY_BYTES = 8 * 1024

internal fun httpResponse(
    status: String,
    contentType: String,
    body: String
): String {
    val bytes = body.toByteArray(Charsets.UTF_8)
    return buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
        append("Content-Length: ").append(bytes.size).append("\r\n")
        append("Connection: close\r\n")
        // This page exists for a few seconds on a home network; make sure no
        // browser or proxy holds on to it.
        append("Cache-Control: no-store\r\n")
        append("\r\n")
        append(body)
    }
}

/** Minimal escaping for the one place text is echoed back. */
internal fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * The form the phone sees.
 *
 * Deliberately one self-contained file with no external assets: the watch is
 * serving this off a socket, and every extra request is another round trip
 * over a link that may be slow.
 */
internal fun formPage(title: String, message: String? = null): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
  :root { color-scheme: dark; }
  body {
    margin: 0; padding: 24px 16px;
    background: #111; color: #fff;
    font: 16px/1.5 system-ui, -apple-system, sans-serif;
  }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p.sub { margin: 0 0 20px; color: #bdbdbd; font-size: 14px; }
  label { display: block; font-size: 13px; color: #bdbdbd; margin: 16px 0 6px; }
  input {
    width: 100%; box-sizing: border-box;
    padding: 14px; font-size: 17px;
    background: #1c1c1e; color: #fff;
    border: 1px solid #3a3a3c; border-radius: 10px;
  }
  input:focus { outline: 2px solid #ff5a5f; border-color: transparent; }
  button {
    width: 100%; margin-top: 20px; padding: 15px;
    font-size: 17px; font-weight: 600;
    background: #ff5a5f; color: #000;
    border: 0; border-radius: 10px;
  }
  .msg { margin-top: 16px; padding: 12px; border-radius: 10px;
         background: #2c1c1e; color: #ff8a8f; font-size: 14px; }
</style>
</head>
<body>
  <h1>$title</h1>
  <p class="sub">Typed here, sent straight to the watch.</p>
  <form method="POST" action="/submit">
    <label for="pin">PIN shown on the watch</label>
    <input id="pin" name="pin" inputmode="numeric" autocomplete="off"
           pattern="[0-9]*" required>
    <label for="text">Text</label>
    <input id="text" name="text" autocomplete="off" autocapitalize="none"
           autocorrect="off" spellcheck="false" autofocus required>
    <button type="submit">Send to watch</button>
  </form>
  ${message?.let { "<div class=\"msg\">${escapeHtml(it)}</div>" } ?: ""}
</body>
</html>
""".trimIndent()

internal fun donePage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sent</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; padding:48px 16px; background:#111; color:#fff;
         font:16px/1.5 system-ui,-apple-system,sans-serif; text-align:center; }
  h1 { font-size:22px; margin:0 0 8px; }
  p { color:#bdbdbd; font-size:14px; margin:0; }
</style>
</head>
<body>
  <h1>Sent to the watch</h1>
  <p>You can close this page. The watch has stopped listening.</p>
</body>
</html>
""".trimIndent()
