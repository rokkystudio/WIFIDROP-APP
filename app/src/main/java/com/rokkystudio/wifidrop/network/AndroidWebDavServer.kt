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
            val request = parseRequest(input) ?: run {
                writeSimpleResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request".toByteArray())
                return
            }

            try {
                when (request.method) {
                    "OPTIONS" -> handleOptions(output)
                    "PROPFIND" -> handlePropFind(output, request)
                    "GET" -> handleGet(output, request, sendBody = true)
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
            "DAV" to "1, 2",
            "MS-Author-Via" to "DAV",
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
        val responseXml = buildMultiStatusXml(target, includeChildren = depth != "0")
            .toByteArray(StandardCharsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "application/xml; charset=utf-8",
            "DAV" to "1, 2",
            "MS-Author-Via" to "DAV",
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
            writeSimpleResponse(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", ByteArray(0))
            return
        }

        val length = node.contentLength().coerceAtLeast(0L)
        val headers = linkedMapOf(
            "Content-Type" to "application/octet-stream",
            "Content-Length" to length.toString(),
        )
        writeResponseHeaders(output, 200, "OK", headers)
        if (!sendBody) {
            output.flush()
            return
        }

        openInputStream(node).use { stream ->
            stream.copyTo(output)
        }
        output.flush()
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

    private fun buildMultiStatusXml(target: ResolvedNode, includeChildren: Boolean): String {
        val responses = mutableListOf(renderPropResponse(target))
        if (includeChildren && target.isDirectory) {
            listChildren(target).forEach { child ->
                responses += renderPropResponse(child)
            }
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<D:multistatus xmlns:D=\"DAV:\">")
            responses.forEach { append(it) }
            append("</D:multistatus>")
        }
    }

    private fun renderPropResponse(node: ResolvedNode): String {
        val isCollection = node.isDirectory
        val href = buildHref(node)
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
            append("<D:getlastmodified>").append(escapeXml(formatRfc1123(node.lastModified()))).append("</D:getlastmodified>")
            append("<D:creationdate>").append(escapeXml(formatIso8601(node.lastModified()))).append("</D:creationdate>")
            append("<D:isreadonly>").append(readOnly).append("</D:isreadonly>")
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
        val headers: Map<String, String>,
        val contentLength: Int,
    )

    private companion object {
        const val LOG_TAG = "WiFiDrop"
        const val SOCKET_TIMEOUT_MS = 10_000
    }
}
