package com.rokkystudio.wifidrop.network

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.rokkystudio.wifidrop.WiFiDropError
import com.rokkystudio.wifidrop.asException
import com.rokkystudio.wifidrop.storage.PublishedStorageRoot
import com.rokkystudio.wifidrop.storage.StorageBackendType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebDAV server for Android storage roots. Publishes one virtual root that exposes
 * multiple top-level storage volumes such as Internal Storage and SD/USB trees.
 */
class AndroidWebDavServer(
    private val context: Context,
) : Closeable {
    data class RunningServer(
        val host: String,
        val port: Int,
        val basePath: String,
        val rootDisplayNames: List<String>,
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var clientExecutor: ExecutorService? = null
    private var publishedRoots: List<PublishedRoot> = emptyList()

    fun start(
        wifiInfo: WifiNetworkProvider.WifiNetworkInfo,
        roots: List<PublishedStorageRoot>,
    ): RunningServer {
        stop()

        if (roots.isEmpty()) {
            throw WiFiDropError.WebDavStartFailed("No published storage roots are available").asException()
        }

        val hostAddress = wifiInfo.ipv4Address.hostAddress
            ?: throw WiFiDropError.WebDavStartFailed("Wi-Fi IP address is missing").asException()
        val resolvedRoots = resolvePublishedRoots(roots)
        if (resolvedRoots.isEmpty()) {
            throw WiFiDropError.WebDavStartFailed("Storage roots could not be resolved").asException()
        }

        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(wifiInfo.ipv4Address as Inet4Address, 0))
        } catch (throwable: Throwable) {
            socket.close()
            throw WiFiDropError.WebDavStartFailed("Could not bind Android WebDAV server").asException(throwable)
        }

        publishedRoots = resolvedRoots
        serverSocket = socket
        clientExecutor = Executors.newCachedThreadPool()
        running.set(true)
        acceptThread = Thread(
            { acceptLoop() },
            "WiFiDrop-WebDavAccept",
        ).also { it.start() }

        val rootNames = resolvedRoots.map { it.displayName }
        Log.d(LOG_TAG, "WebDAV started on $hostAddress:${socket.localPort} roots=${rootNames.joinToString()}")
        return RunningServer(
            host = hostAddress,
            port = socket.localPort,
            basePath = "/",
            rootDisplayNames = rootNames,
        )
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        acceptThread?.join(500)
        acceptThread = null
        clientExecutor?.shutdownNow()
        clientExecutor = null
        publishedRoots = emptyList()
    }

    override fun close() {
        stop()
    }

    private fun resolvePublishedRoots(roots: List<PublishedStorageRoot>): List<PublishedRoot> {
        return roots.mapNotNull { root ->
            when (root.backendType) {
                StorageBackendType.DIRECT_FILE -> {
                    val directory = root.directPath?.let(::File)
                    if (directory?.exists() == true && directory.isDirectory) {
                        PublishedRoot(
                            id = root.id,
                            displayName = root.displayName,
                            isWritable = root.isWritable,
                            backend = PublishedRootBackend.Direct(directory),
                        )
                    } else {
                        null
                    }
                }

                StorageBackendType.SAF_TREE -> {
                    val document = root.treeUri
                        ?.let { DocumentFile.fromTreeUri(context, it) }
                        ?.takeIf { it.exists() && it.isDirectory }
                    if (document != null) {
                        PublishedRoot(
                            id = root.id,
                            displayName = root.displayName,
                            isWritable = root.isWritable,
                            backend = PublishedRootBackend.Saf(document),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running.get()) {
            try {
                val client = socket.accept()
                clientExecutor?.execute { handleClient(client) }
            } catch (_: SocketException) {
                break
            } catch (throwable: Throwable) {
                Log.d(LOG_TAG, "WebDAV accept failed", throwable)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = try {
                parseRequest(input)
            } catch (_: SocketTimeoutException) {
                Log.d(LOG_TAG, "WebDAV request read timed out")
                return
            } catch (_: EOFException) {
                return
            } catch (throwable: Throwable) {
                Log.d(LOG_TAG, "WebDAV request parse failed", throwable)
                writeSimpleResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request".toByteArray())
                return
            } ?: run {
                writeSimpleResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request".toByteArray())
                return
            }
            Log.d(
                LOG_TAG,
                "WebDAV request ${request.method} ${request.path} host=${request.headers["host"].orEmpty()} " +
                    "depth=${request.headers["depth"].orEmpty()} auth=${request.headers.containsKey("authorization")}",
            )

            try {
                when (request.method) {
                    "OPTIONS" -> handleOptions(output)
                    "PROPFIND" -> handlePropFind(output, request)
                    "GET" -> {
                        if (request.path == API_META_PATH) {
                            handleApiMeta(output, request)
                        } else if (request.path == API_LIST_PATH) {
                            handleApiList(output, request)
                        } else {
                            handleGet(output, request, sendBody = true)
                        }
                    }
                    "HEAD" -> handleGet(output, request, sendBody = false)
                    "PUT" -> handlePut(output, request, input)
                    "DELETE" -> handleDelete(output, request)
                    "MKCOL" -> handleMkCol(output, request)
                    "MOVE" -> handleMove(output, request)
                    "LOCK" -> handleLock(output, request)
                    "UNLOCK" -> handleUnlock(output)
                    else -> writeSimpleResponse(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", ByteArray(0))
                }
            } catch (throwable: Throwable) {
                Log.d(LOG_TAG, "WebDAV request failed: ${request.method} ${request.path}", throwable)
                writeSimpleResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
            }
        }
    }

    private fun handleOptions(output: BufferedOutputStream) {
        val headers = linkedMapOf(
            "Allow" to "OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, LOCK, UNLOCK",
            "Public" to "OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, LOCK, UNLOCK",
            "DAV" to "1, 2",
            "MS-Author-Via" to "DAV",
            "Accept-Ranges" to "bytes",
            "Content-Length" to "0",
        )
        writeResponse(output, 200, "OK", headers, null)
    }

    private fun handlePropFind(output: BufferedOutputStream, request: WebDavRequest) {
        val target = resolvePath(request.path)
        if (!target.exists) {
            writeSimpleResponse(output, 404, "Not Found", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val depth = request.headers["depth"] ?: "infinity"
        val responseXml = buildMultiStatusXml(
            target = target,
            includeChildren = depth != "0",
            requestBaseUrl = buildRequestBaseUrl(request),
        )
            .toByteArray(StandardCharsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "text/xml; charset=utf-8",
            "DAV" to "1, 2",
            "MS-Author-Via" to "DAV",
            "Accept-Ranges" to "bytes",
            "Content-Length" to responseXml.size.toString(),
        )
        writeResponse(output, 207, "Multi-Status", headers, responseXml)
    }

    private fun handleGet(output: BufferedOutputStream, request: WebDavRequest, sendBody: Boolean) {
        val node = resolvePath(request.path)
        if (!node.exists) {
            writeSimpleResponse(output, 404, "Not Found", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (node.isDirectory) {
            val body = buildDirectoryListingHtml(node)
                .toByteArray(StandardCharsets.UTF_8)
            val headers = linkedMapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Length" to body.size.toString(),
            )
            writeResponseHeaders(output, 200, "OK", headers)
            if (sendBody) {
                output.write(body)
            }
            output.flush()
            return
        }

        val length = node.contentLength().coerceAtLeast(0L)
        val requestedRange = request.headers["range"]?.let { parseByteRange(it, length) }
        if (request.headers.containsKey("range") && requestedRange == null) {
            val headers = linkedMapOf(
                "Content-Range" to "bytes */$length",
                "Content-Length" to "0",
            )
            writeResponse(output, 416, "Range Not Satisfiable", headers, null)
            return
        }
        val responseOffset = requestedRange?.start ?: 0L
        val responseLength = requestedRange?.length ?: length
        val headers = linkedMapOf(
            "Content-Type" to "application/octet-stream",
            "Content-Length" to responseLength.toString(),
            "ETag" to buildEtag(node),
            "Accept-Ranges" to "bytes",
        )
        if (requestedRange != null) {
            headers["Content-Range"] = "bytes ${requestedRange.start}-${requestedRange.endInclusive}/$length"
        }
        writeResponseHeaders(output, if (requestedRange != null) 206 else 200, if (requestedRange != null) "Partial Content" else "OK", headers)
        if (!sendBody) {
            output.flush()
            return
        }

        openInputStream(node).use { stream ->
            skipFully(stream, responseOffset)
            copyExactly(stream, output, responseLength)
        }
        output.flush()
    }

    private fun handleApiMeta(output: BufferedOutputStream, request: WebDavRequest) {
        val targetPath = parseQueryParams(request.query)["path"].orEmpty().ifBlank { "/" }
        val node = resolvePath(targetPath)
        if (!node.exists) {
            writeSimpleResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "ok=0\nerror=not_found\n".toByteArray(StandardCharsets.UTF_8))
            return
        }

        val body = buildString {
            append("ok=1\n")
            append("directory=").append(if (node.isDirectory) "1" else "0").append('\n')
            append("size=").append(node.contentLength().coerceAtLeast(0L)).append('\n')
            append("lastModified=").append(node.lastModified().coerceAtLeast(0L)).append('\n')
            append("writable=").append(if (node.isWritable) "1" else "0").append('\n')
            append("name=").append(Uri.encode(node.displayName)).append('\n')
            append("etag=").append(Uri.encode(buildEtag(node))).append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
        writeResponse(
            output,
            200,
            "OK",
            linkedMapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
            ),
            body,
        )
    }

    private fun handleApiList(output: BufferedOutputStream, request: WebDavRequest) {
        val targetPath = parseQueryParams(request.query)["path"].orEmpty().ifBlank { "/" }
        val node = resolvePath(targetPath)
        if (!node.exists) {
            writeSimpleResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "ok=0\nerror=not_found\n".toByteArray(StandardCharsets.UTF_8))
            return
        }
        if (!node.isDirectory) {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", "ok=0\nerror=not_directory\n".toByteArray(StandardCharsets.UTF_8))
            return
        }

        val body = buildString {
            append("ok=1\n")
            listChildren(node).forEach { child ->
                append("entry=")
                append(Uri.encode(child.displayName))
                append('\t')
                append(if (child.isDirectory) "1" else "0")
                append('\t')
                append(child.contentLength().coerceAtLeast(0L))
                append('\t')
                append(child.lastModified().coerceAtLeast(0L))
                append('\t')
                append(if (child.isWritable) "1" else "0")
                append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
        writeResponse(
            output,
            200,
            "OK",
            linkedMapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
            ),
            body,
        )
    }

    private fun buildDirectoryListingHtml(node: ResolvedNode): String {
        val children = listChildren(node)
        val title = if (node.isVirtualRoot) "/" else node.displayName
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<title>").append(escapeXml(title)).append("</title>")
            append("<style>")
            append("body{font-family:Segoe UI,Arial,sans-serif;margin:24px;line-height:1.4;}")
            append("h1{font-size:20px;margin:0 0 16px;}ul{list-style:none;padding:0;margin:0;}")
            append("li{margin:6px 0;}a{text-decoration:none;}a:hover{text-decoration:underline;}")
            append(".meta{color:#666;font-size:12px;margin-left:8px;}")
            append("</style></head><body>")
            append("<h1>").append(escapeXml(title)).append("</h1>")
            append("<ul>")
            buildParentLink(node)?.let { parentHref ->
                append("<li><a href=\"").append(escapeXml(parentHref)).append("\">..</a></li>")
            }
            children.forEach { child ->
                val href = buildHref(child)
                append("<li><a href=\"").append(escapeXml(href)).append("\">")
                append(escapeXml(child.displayName))
                if (child.isDirectory) {
                    append("/")
                }
                append("</a>")
                if (!child.isDirectory) {
                    append("<span class=\"meta\">").append(child.contentLength().coerceAtLeast(0L)).append(" bytes</span>")
                }
                append("</li>")
            }
            append("</ul></body></html>")
        }
    }

    private fun buildParentLink(node: ResolvedNode): String? {
        if (node.isVirtualRoot || node.segments.isEmpty()) {
            return null
        }
        val parentSegments = node.segments.dropLast(1)
        return if (parentSegments.isEmpty()) {
            "/"
        } else {
            val encodedSegments = parentSegments.joinToString("/") { Uri.encode(it) }
            "/$encodedSegments/"
        }
    }

    private fun handlePut(output: BufferedOutputStream, request: WebDavRequest, input: InputStream) {
        if (request.contentLength < 0) {
            writeSimpleResponse(output, 411, "Length Required", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val parentResolution = resolveParentPath(request.path) ?: run {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (parentResolution.parent.isVirtualRoot || parentResolution.parent.isPublishedRootEntry) {
            writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (!parentResolution.parent.isWritable) {
            writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val existing = resolvePath(request.path)
        if (existing.exists && existing.isDirectory) {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        when {
            parentResolution.parent.directFile != null -> {
                val parentDirectory = parentResolution.parent.directFile
                val targetFile = File(parentDirectory, parentResolution.childName)
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile, false).use { fileOutput ->
                    copyExactly(input, fileOutput, request.contentLength.toLong())
                }
            }

            parentResolution.parent.safDocument != null -> {
                val parentDocument = parentResolution.parent.safDocument
                val existingDocument = parentDocument.findFile(parentResolution.childName)
                if (existingDocument?.isDirectory == true) {
                    writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
                    return
                }
                val targetDocument = existingDocument
                    ?: parentDocument.createFile("application/octet-stream", parentResolution.childName)
                    ?: throw IOException("Could not create SAF document")
                context.contentResolver.openOutputStream(targetDocument.uri, "rwt")?.use { stream ->
                    copyExactly(input, stream, request.contentLength.toLong())
                } ?: throw IOException("Could not open SAF output stream")
            }

            else -> {
                writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
                return
            }
        }

        val statusCode = if (existing.exists) 204 else 201
        val reason = if (existing.exists) "No Content" else "Created"
        writeSimpleResponse(output, statusCode, reason, "text/plain; charset=utf-8", ByteArray(0))
    }

    private fun handleDelete(output: BufferedOutputStream, request: WebDavRequest) {
        val node = resolvePath(request.path)
        if (!node.exists) {
            writeSimpleResponse(output, 404, "Not Found", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (node.isVirtualRoot || node.isPublishedRootEntry || !node.isWritable) {
            writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val deleted = when {
            node.directFile != null -> deleteDirect(node.directFile)
            node.safDocument != null -> deleteSaf(node.safDocument)
            else -> false
        }
        if (!deleted) {
            writeSimpleResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        writeSimpleResponse(output, 204, "No Content", "text/plain; charset=utf-8", ByteArray(0))
    }

    private fun handleMkCol(output: BufferedOutputStream, request: WebDavRequest) {
        val parentResolution = resolveParentPath(request.path) ?: run {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (parentResolution.parent.isVirtualRoot || parentResolution.parent.isPublishedRootEntry || !parentResolution.parent.isWritable) {
            writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (resolvePath(request.path).exists) {
            writeSimpleResponse(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val created = when {
            parentResolution.parent.directFile != null -> File(parentResolution.parent.directFile, parentResolution.childName).mkdirs()
            parentResolution.parent.safDocument != null -> {
                parentResolution.parent.safDocument.createDirectory(parentResolution.childName) != null
            }

            else -> false
        }
        if (!created) {
            writeSimpleResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        writeSimpleResponse(output, 201, "Created", "text/plain; charset=utf-8", ByteArray(0))
    }

    private fun handleMove(output: BufferedOutputStream, request: WebDavRequest) {
        val source = resolvePath(request.path)
        if (!source.exists) {
            writeSimpleResponse(output, 404, "Not Found", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (source.isVirtualRoot || source.isPublishedRootEntry || !source.isWritable) {
            writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val destinationHeader = request.headers["destination"].orEmpty()
        if (destinationHeader.isBlank()) {
            writeSimpleResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        val destinationPath = normalizeRequestPath(destinationHeader)
        val destinationParent = resolveParentPath(destinationPath) ?: run {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (destinationParent.parent.isVirtualRoot || destinationParent.parent.isPublishedRootEntry || !destinationParent.parent.isWritable) {
            writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (source.root?.id != destinationParent.parent.root?.id) {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val destinationNode = resolvePath(destinationPath)
        val overwrite = request.headers["overwrite"]?.equals("T", ignoreCase = true) ?: true
        if (destinationNode.exists) {
            if (!overwrite) {
                writeSimpleResponse(output, 412, "Precondition Failed", "text/plain; charset=utf-8", ByteArray(0))
                return
            }
            if (destinationNode.isVirtualRoot || destinationNode.isPublishedRootEntry || !destinationNode.isWritable) {
                writeSimpleResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", ByteArray(0))
                return
            }
            val deleted = when {
                destinationNode.directFile != null -> deleteDirect(destinationNode.directFile)
                destinationNode.safDocument != null -> deleteSaf(destinationNode.safDocument)
                else -> false
            }
            if (!deleted) {
                writeSimpleResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
                return
            }
        }

        val sourceParent = resolveParentPath(request.path) ?: run {
            writeSimpleResponse(output, 409, "Conflict", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        val moved = when {
            source.directFile != null && destinationParent.parent.directFile != null -> {
                val targetFile = File(destinationParent.parent.directFile, destinationParent.childName)
                targetFile.parentFile?.mkdirs()
                source.directFile.renameTo(targetFile)
            }

            source.safDocument != null && sourceParent.parent.safDocument != null && destinationParent.parent.safDocument != null -> {
                moveSafDocument(
                    sourceDocument = source.safDocument,
                    sourceParent = sourceParent.parent.safDocument,
                    destinationParent = destinationParent.parent.safDocument,
                    destinationName = destinationParent.childName,
                )
            }

            else -> false
        }
        if (!moved) {
            writeSimpleResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        writeSimpleResponse(output, 201, "Created", "text/plain; charset=utf-8", ByteArray(0))
    }

    private fun handleLock(output: BufferedOutputStream, request: WebDavRequest) {
        val lockToken = "opaquelocktoken:${UUID.randomUUID()}"
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>")
            append("<D:locktype><D:write/></D:locktype>")
            append("<D:lockscope><D:exclusive/></D:lockscope>")
            append("<D:depth>Infinity</D:depth>")
            append("<D:locktoken><D:href>").append(escapeXml(lockToken)).append("</D:href></D:locktoken>")
            append("</D:activelock></D:lockdiscovery></D:prop>")
        }.toByteArray(StandardCharsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "application/xml; charset=utf-8",
            "Lock-Token" to "<$lockToken>",
            "Content-Length" to body.size.toString(),
        )
        writeResponse(output, if (resolvePath(request.path).exists) 200 else 201, "OK", headers, body)
    }

    private fun handleUnlock(output: BufferedOutputStream) {
        writeSimpleResponse(output, 204, "No Content", "text/plain; charset=utf-8", ByteArray(0))
    }

    private fun parseRequest(input: InputStream): WebDavRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) {
            return null
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            return null
        }

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) {
                break
            }

            val separator = line.indexOf(':')
            if (separator <= 0) {
                continue
            }
            val key = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            headers[key] = value
        }

        return WebDavRequest(
            method = parts[0].uppercase(Locale.US),
            path = normalizeRequestPath(parts[1]),
            query = parts[1].substringAfter('?', ""),
            headers = headers,
            contentLength = headers["content-length"]?.toIntOrNull() ?: 0,
        )
    }

    private fun resolvePath(requestPath: String): ResolvedNode {
        return resolveSegments(splitPathSegments(normalizeRequestPath(requestPath)))
    }

    private fun resolveSegments(segments: List<String>): ResolvedNode {
        if (segments.isEmpty()) {
            return ResolvedNode(
                root = null,
                segments = emptyList(),
                exists = true,
                isDirectory = true,
                isVirtualRoot = true,
                isPublishedRootEntry = false,
            )
        }

        val root = publishedRoots.firstOrNull { it.displayName == segments.first() }
            ?: return ResolvedNode(
                root = null,
                segments = segments,
                exists = false,
                isDirectory = false,
                isVirtualRoot = false,
                isPublishedRootEntry = false,
            )

        val relativeSegments = segments.drop(1)
        if (relativeSegments.isEmpty()) {
            return when (val backend = root.backend) {
                is PublishedRootBackend.Direct -> ResolvedNode(
                    root = root,
                    segments = segments,
                    directFile = backend.directory,
                    exists = true,
                    isDirectory = true,
                    isVirtualRoot = false,
                    isPublishedRootEntry = true,
                )

                is PublishedRootBackend.Saf -> ResolvedNode(
                    root = root,
                    segments = segments,
                    safDocument = backend.document,
                    exists = true,
                    isDirectory = true,
                    isVirtualRoot = false,
                    isPublishedRootEntry = true,
                )
            }
        }

        return when (val backend = root.backend) {
            is PublishedRootBackend.Direct -> resolveDirectNode(root, segments, relativeSegments, backend.directory)
            is PublishedRootBackend.Saf -> resolveSafNode(root, segments, relativeSegments, backend.document)
        }
    }

    private fun resolveDirectNode(
        root: PublishedRoot,
        fullSegments: List<String>,
        relativeSegments: List<String>,
        rootDirectory: File,
    ): ResolvedNode {
        var current = rootDirectory
        for (segment in relativeSegments) {
            current = File(current, segment)
            if (!current.exists()) {
                return ResolvedNode(
                    root = root,
                    segments = fullSegments,
                    directFile = current,
                    exists = false,
                    isDirectory = false,
                    isVirtualRoot = false,
                    isPublishedRootEntry = false,
                )
            }
        }
        return ResolvedNode(
            root = root,
            segments = fullSegments,
            directFile = current,
            exists = true,
            isDirectory = current.isDirectory,
            isVirtualRoot = false,
            isPublishedRootEntry = false,
        )
    }

    private fun resolveSafNode(
        root: PublishedRoot,
        fullSegments: List<String>,
        relativeSegments: List<String>,
        rootDocument: DocumentFile,
    ): ResolvedNode {
        var current = rootDocument
        for (segment in relativeSegments) {
            current = current.findFile(segment) ?: return ResolvedNode(
                root = root,
                segments = fullSegments,
                exists = false,
                isDirectory = false,
                isVirtualRoot = false,
                isPublishedRootEntry = false,
            )
        }
        return ResolvedNode(
            root = root,
            segments = fullSegments,
            safDocument = current,
            exists = true,
            isDirectory = current.isDirectory,
            isVirtualRoot = false,
            isPublishedRootEntry = false,
        )
    }

    private fun resolveParentPath(requestPath: String): ParentResolution? {
        val segments = splitPathSegments(normalizeRequestPath(requestPath))
        if (segments.isEmpty()) {
            return null
        }
        val childName = segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parent = resolveSegments(segments.dropLast(1))
        if (!parent.exists || !parent.isDirectory) {
            return null
        }
        return ParentResolution(parent = parent, childName = childName)
    }

    private fun listChildren(node: ResolvedNode): List<ResolvedNode> {
        if (!node.exists || !node.isDirectory) {
            return emptyList()
        }
        if (node.isVirtualRoot) {
            return publishedRoots.map { root ->
                resolveSegments(listOf(root.displayName))
            }.sortedBy { it.displayName.lowercase(Locale.US) }
        }
        val baseSegments = node.segments
        return when {
            node.directFile != null -> node.directFile.listFiles()
                .orEmpty()
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
                .map { child ->
                    ResolvedNode(
                        root = node.root,
                        segments = baseSegments + child.name,
                        directFile = child,
                        exists = true,
                        isDirectory = child.isDirectory,
                        isVirtualRoot = false,
                        isPublishedRootEntry = false,
                    )
                }

            node.safDocument != null -> node.safDocument.listFiles()
                .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name.orEmpty().lowercase(Locale.US) }))
                .map { child ->
                    ResolvedNode(
                        root = node.root,
                        segments = baseSegments + child.name.orEmpty(),
                        safDocument = child,
                        exists = true,
                        isDirectory = child.isDirectory,
                        isVirtualRoot = false,
                        isPublishedRootEntry = false,
                    )
                }

            else -> emptyList()
        }
    }

    private fun buildMultiStatusXml(
        target: ResolvedNode,
        includeChildren: Boolean,
        requestBaseUrl: String,
    ): String {
        val responses = mutableListOf(renderPropResponse(target, requestBaseUrl))
        if (includeChildren && target.isDirectory) {
            listChildren(target).forEach { child ->
                responses += renderPropResponse(child, requestBaseUrl)
            }
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<D:multistatus xmlns:D=\"DAV:\">")
            responses.forEach { append(it) }
            append("</D:multistatus>")
        }
    }

    private fun renderPropResponse(node: ResolvedNode, requestBaseUrl: String): String {
        val isCollection = node.isDirectory
        val href = buildAbsoluteHref(node, requestBaseUrl)
        val contentLength = if (isCollection) {
            ""
        } else {
            "<D:getcontentlength>${node.contentLength().coerceAtLeast(0L)}</D:getcontentlength>"
        }
        val contentType = if (isCollection) {
            ""
        } else {
            "<D:getcontenttype>application/octet-stream</D:getcontenttype>"
        }
        val etag = if (isCollection) {
            ""
        } else {
            "<D:getetag>${escapeXml(buildEtag(node))}</D:getetag>"
        }
        val resourceType = if (isCollection) {
            "<D:resourcetype><D:collection/></D:resourcetype>"
        } else {
            "<D:resourcetype/>"
        }
        val lockSupport =
            "<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>" +
                "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>"
        val readOnly = if (node.isWritable) "0" else "1"
        return buildString {
            append("<D:response>")
            append("<D:href>").append(escapeXml(href)).append("</D:href>")
            append("<D:propstat><D:prop>")
            append("<D:displayname>").append(escapeXml(node.displayName)).append("</D:displayname>")
            append(resourceType)
            append(contentLength)
            append(contentType)
            append(etag)
            append("<D:iscollection>").append(if (isCollection) "1" else "0").append("</D:iscollection>")
            append("<D:getlastmodified>").append(escapeXml(formatRfc1123(node.lastModified()))).append("</D:getlastmodified>")
            append("<D:creationdate>").append(escapeXml(formatIso8601(node.lastModified()))).append("</D:creationdate>")
            append("<D:isreadonly>").append(readOnly).append("</D:isreadonly>")
            append("<D:ishidden>0</D:ishidden>")
            append(lockSupport)
            append("<D:lockdiscovery/>")
            append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
            append("</D:response>")
        }
    }

    private fun buildHref(node: ResolvedNode): String {
        if (node.isVirtualRoot || node.segments.isEmpty()) {
            return "/"
        }
        val encodedSegments = node.segments.joinToString("/") { Uri.encode(it) }
        return if (node.isDirectory) "/$encodedSegments/" else "/$encodedSegments"
    }

    private fun buildAbsoluteHref(node: ResolvedNode, requestBaseUrl: String): String {
        val relativeHref = buildHref(node)
        if (requestBaseUrl.isBlank()) {
            return relativeHref
        }
        return if (relativeHref == "/") {
            "$requestBaseUrl/"
        } else {
            requestBaseUrl + relativeHref
        }
    }

    private fun buildRequestBaseUrl(request: WebDavRequest): String {
        val host = request.headers["host"].orEmpty().trim()
        if (host.isBlank()) {
            return ""
        }
        return "http://$host"
    }

    private fun buildEtag(node: ResolvedNode): String {
        return "\"${node.lastModified()}-${node.contentLength().coerceAtLeast(0L)}\""
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) {
            return emptyMap()
        }
        return query.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) {
                    return@mapNotNull null
                }
                val separator = part.indexOf('=')
                val key = if (separator >= 0) part.substring(0, separator) else part
                val value = if (separator >= 0) part.substring(separator + 1) else ""
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
            .toMap()
    }

    private fun parseByteRange(headerValue: String, length: Long): ByteRange? {
        if (!headerValue.startsWith("bytes=", ignoreCase = true) || length < 0L) {
            return null
        }
        val rangeValue = headerValue.substringAfter('=').trim()
        val separator = rangeValue.indexOf('-')
        if (separator < 0) {
            return null
        }
        val startValue = rangeValue.substring(0, separator).trim()
        val endValue = rangeValue.substring(separator + 1).trim()
        if (startValue.isEmpty()) {
            val suffixLength = endValue.toLongOrNull() ?: return null
            if (suffixLength <= 0L) {
                return null
            }
            val boundedSuffix = minOf(suffixLength, length)
            return ByteRange(
                start = (length - boundedSuffix).coerceAtLeast(0L),
                endInclusive = (length - 1L).coerceAtLeast(0L),
            )
        }

        val start = startValue.toLongOrNull() ?: return null
        if (start < 0L || start >= length) {
            return null
        }
        val end = if (endValue.isEmpty()) {
            length - 1L
        } else {
            minOf(endValue.toLongOrNull() ?: return null, length - 1L)
        }
        if (end < start) {
            return null
        }
        return ByteRange(start = start, endInclusive = end)
    }

    private fun openInputStream(node: ResolvedNode): InputStream {
        node.directFile?.let { return FileInputStream(it) }
        node.safDocument?.let { document ->
            return context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Could not open SAF input stream")
        }
        throw FileNotFoundException("Node is not a file")
    }

    private fun moveSafDocument(
        sourceDocument: DocumentFile,
        sourceParent: DocumentFile,
        destinationParent: DocumentFile,
        destinationName: String,
    ): Boolean {
        return try {
            var currentUri = if (sourceParent.uri == destinationParent.uri) {
                sourceDocument.uri
            } else {
                DocumentsContract.moveDocument(
                    context.contentResolver,
                    sourceDocument.uri,
                    sourceParent.uri,
                    destinationParent.uri,
                ) ?: return false
            }
            val currentName = sourceDocument.name.orEmpty()
            if (currentName != destinationName) {
                currentUri = DocumentsContract.renameDocument(
                    context.contentResolver,
                    currentUri,
                    destinationName,
                ) ?: return false
            }
            currentUri != Uri.EMPTY
        } catch (throwable: Throwable) {
            Log.d(LOG_TAG, "moveSafDocument failed", throwable)
            false
        }
    }

    private fun deleteDirect(file: File): Boolean {
        if (!file.exists()) {
            return true
        }
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { child ->
                if (!deleteDirect(child)) {
                    return false
                }
            }
        }
        return file.delete() || !file.exists()
    }

    private fun deleteSaf(document: DocumentFile): Boolean {
        if (document.isDirectory) {
            document.listFiles().forEach { child ->
                if (!deleteSaf(child)) {
                    return false
                }
            }
        }
        return document.delete()
    }

    private fun splitPathSegments(path: String): List<String> {
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }

    private fun normalizeRequestPath(value: String): String {
        val rawValue = value.substringBefore('?')
        val path = if (rawValue.startsWith("http://") || rawValue.startsWith("https://")) {
            Uri.parse(rawValue).encodedPath ?: "/"
        } else {
            rawValue
        }
        return URLDecoder.decode(path.ifBlank { "/" }, StandardCharsets.UTF_8.name())
    }

    private fun writeSimpleResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
    ) {
        val headers = linkedMapOf(
            "Content-Type" to contentType,
            "Content-Length" to body.size.toString(),
        )
        writeResponse(output, statusCode, reason, headers, body)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ) {
        writeResponseHeaders(output, statusCode, reason, headers)
        if (body != null) {
            output.write(body)
        }
        output.flush()
    }

    private fun writeResponseHeaders(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        headers: Map<String, String>,
    ) {
        val headerBuilder = StringBuilder()
        headerBuilder.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
        headerBuilder.append("Date: ").append(formatRfc1123(System.currentTimeMillis())).append("\r\n")
        headerBuilder.append("Server: WiFiDrop-WebDAV/1.0\r\n")
        headers.forEach { (key, value) ->
            headerBuilder.append(key).append(": ").append(value).append("\r\n")
        }
        headerBuilder.append("Connection: close\r\n\r\n")
        output.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.isEmpty()) null else ByteArray(bytes.size) { index -> bytes[index] }.toString(StandardCharsets.UTF_8)
            }
            if (value == '\n'.code) {
                break
            }
            if (value != '\r'.code) {
                bytes += value.toByte()
            }
        }
        return ByteArray(bytes.size) { index -> bytes[index] }.toString(StandardCharsets.UTF_8)
    }

    private fun copyExactly(input: InputStream, output: OutputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) {
                throw EOFException("Request body ended before Content-Length bytes were read")
            }
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val value = input.read()
            if (value < 0) {
                throw EOFException("Input stream ended before requested offset")
            }
            remaining--
        }
    }

    private fun formatRfc1123(timestamp: Long): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()))
    }

    private fun formatIso8601(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()))
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private data class PublishedRoot(
        val id: String,
        val displayName: String,
        val isWritable: Boolean,
        val backend: PublishedRootBackend,
    )

    private sealed interface PublishedRootBackend {
        data class Direct(val directory: File) : PublishedRootBackend

        data class Saf(val document: DocumentFile) : PublishedRootBackend
    }

    private data class ResolvedNode(
        val root: PublishedRoot?,
        val segments: List<String>,
        val directFile: File? = null,
        val safDocument: DocumentFile? = null,
        val exists: Boolean,
        val isDirectory: Boolean,
        val isVirtualRoot: Boolean,
        val isPublishedRootEntry: Boolean,
    ) {
        val isWritable: Boolean
            get() = root?.isWritable == true && !isVirtualRoot

        val displayName: String
            get() = when {
                isVirtualRoot -> "/"
                segments.isEmpty() -> "/"
                else -> segments.last()
            }

        fun contentLength(): Long {
            return when {
                directFile != null -> directFile.length()
                safDocument != null -> safDocument.length().coerceAtLeast(0L)
                else -> 0L
            }
        }

        fun lastModified(): Long {
            return when {
                directFile != null -> directFile.lastModified()
                safDocument != null -> safDocument.lastModified()
                else -> System.currentTimeMillis()
            }
        }
    }

    private data class ParentResolution(
        val parent: ResolvedNode,
        val childName: String,
    )

    private data class WebDavRequest(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val contentLength: Int,
    )

    private data class ByteRange(
        val start: Long,
        val endInclusive: Long,
    ) {
        val length: Long
            get() = endInclusive - start + 1L
    }

    private companion object {
        const val LOG_TAG = "WiFiDrop"
        const val SOCKET_TIMEOUT_MS = 10_000
        const val API_META_PATH = "/.wifidropfs/meta"
        const val API_LIST_PATH = "/.wifidropfs/list"
    }
}
