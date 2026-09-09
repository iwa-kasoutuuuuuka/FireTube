package com.firetube.tv.cast

import android.content.Context
import android.content.Intent
import android.util.Log
import com.firetube.tv.ui.player.PlaybackActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 超軽量ローカルHTTPキャストサーバー
 * Android標準の java.net.ServerSocket のみで動作し、
 * 追加ライブラリ不要（0KB追加）、低RAM（数KB）で稼働
 */
object LocalCastServer {

    private const val TAG = "LocalCastServer"
    const val PORT = 8080

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start(context: Context) {
        if (serverJob != null && serverJob?.isActive == true) return

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "LocalCastServer started on port $PORT")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket, context.applicationContext)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server socket error", e)
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverJob?.cancel()
        serverSocket = null
        serverJob = null
        Log.i(TAG, "LocalCastServer stopped")
    }

    /**
     * テレビ端末のローカルIPアドレス（Wi-Fi等）を取得
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return "127.0.0.1"
    }

    private fun handleClient(socket: Socket, context: Context) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val out: OutputStream = s.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val path = parts[1]

                // ヘッダー読み飛ばしおよびContent-Length取得
                var contentLength = 0
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                // POSTボディ読み込み
                var postBody = ""
                if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(buffer, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    postBody = String(buffer, 0, read)
                }

                if (path == "/" || path.startsWith("/?")) {
                    sendWebPage(out)
                } else if (path.startsWith("/play")) {
                    var urlParam = ""
                    if (method.equals("POST", ignoreCase = true)) {
                        for (param in postBody.split("&")) {
                            val pair = param.split("=", limit = 2)
                            if (pair.size == 2 && pair[0] == "url") {
                                urlParam = URLDecoder.decode(pair[1], "UTF-8")
                            }
                        }
                    } else {
                        val query = if (path.contains("?")) path.substringAfter("?") else ""
                        for (param in query.split("&")) {
                            val pair = param.split("=", limit = 2)
                            if (pair.size == 2 && pair[0] == "url") {
                                urlParam = URLDecoder.decode(pair[1], "UTF-8")
                            }
                        }
                    }

                    val videoId = extractVideoId(urlParam)
                    if (videoId != null && videoId.isNotEmpty()) {
                        val intent = Intent(context, PlaybackActivity::class.java).apply {
                            putExtra(PlaybackActivity.EXTRA_VIDEO_ID, videoId)
                            putExtra(PlaybackActivity.EXTRA_VIDEO_TITLE, "キャスト動画")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(intent)
                        sendSuccessPage(out, videoId)
                    } else {
                        sendError(out, "URLが無効です。YouTubeのURLを入力してください。")
                    }
                } else {
                    sendNotFound(out)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling cast request", e)
        }
    }

    private fun sendWebPage(out: OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>FireTube キャスト</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        background-color: #121212;
                        color: #FFFFFF;
                        padding: 24px;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 80vh;
                        margin: 0;
                    }
                    .container {
                        max-width: 480px;
                        width: 100%;
                        background: #1E1E1E;
                        padding: 32px 24px;
                        border-radius: 16px;
                        box-shadow: 0 8px 24px rgba(0,0,0,0.5);
                        text-align: center;
                    }
                    h1 { color: #E50914; margin-bottom: 8px; font-size: 28px; }
                    p { color: #AAAAAA; font-size: 14px; margin-bottom: 24px; }
                    input[type="text"] {
                        width: 100%;
                        padding: 14px;
                        border-radius: 8px;
                        border: 1px solid #333;
                        background: #2A2A2A;
                        color: #FFF;
                        font-size: 16px;
                        box-sizing: border-box;
                        margin-bottom: 16px;
                    }
                    button {
                        width: 100%;
                        padding: 14px;
                        border: none;
                        border-radius: 8px;
                        background: #E50914;
                        color: #FFF;
                        font-size: 16px;
                        font-weight: bold;
                        cursor: pointer;
                    }
                    button:active { background: #B20710; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>FireTube</h1>
                    <p>YouTubeの動画URLを入力してテレビで再生</p>
                    <form action="/play" method="POST">
                        <input type="text" name="url" placeholder="https://www.youtube.com/watch?v=..." required autofocus>
                        <button type="submit">Fire TV で再生</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()
        sendResponse(out, 200, "OK", "text/html; charset=UTF-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun sendSuccessPage(out: OutputStream, videoId: String) {
        val html = """
            <!DOCTYPE html>
            <html lang="ja">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>再生開始</title>
            <style>body{background:#121212;color:#FFF;text-align:center;padding:48px;font-family:sans-serif;}h2{color:#00AA55;}a{color:#E50914;text-decoration:none;font-weight:bold;}</style></head>
            <body><h2>Fire TVで再生を開始しました！</h2><p>動画ID: $videoId</p><br><a href="/">別の動画を送信</a></body></html>
        """.trimIndent()
        sendResponse(out, 200, "OK", "text/html; charset=UTF-8", html.toByteArray(Charsets.UTF_8))
    }

    private fun sendError(out: OutputStream, msg: String) {
        sendResponse(out, 400, "Bad Request", "text/plain; charset=UTF-8", msg.toByteArray(Charsets.UTF_8))
    }

    private fun sendNotFound(out: OutputStream) {
        sendResponse(out, 404, "Not Found", "text/plain; charset=UTF-8", "404 Not Found".toByteArray(Charsets.UTF_8))
    }

    private fun sendResponse(out: OutputStream, code: Int, reason: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun extractVideoId(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.length == 11 && !url.contains("/") && !url.contains("?")) return url
        val pattern = "(?:youtu\\.be\\/|youtube\\.com\\/(?:embed\\/|v\\/|shorts\\/|watch\\?v=|watch\\?.+&v=))([\\w-]{11})".toRegex()
        val match = pattern.find(url)?.groupValues?.get(1)
        if (match != null) return match
        if (url.contains("v=")) {
            val vParam = url.substringAfter("v=").substringBefore("&").substringBefore("#")
            if (vParam.length == 11) return vParam
        }
        return null
    }
}
