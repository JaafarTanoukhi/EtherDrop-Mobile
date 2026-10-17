package expo.modules.etherdrop

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.StatFs
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidStorage(
  private val context: Context,
  private val conflict: (String) -> String,
) : EtherDropEngine.Storage {
  private val resolver get() = context.contentResolver
  data class Document(val uri: Uri, val name: String, val type: String, val size: Long, val modified: Long) {
    val folder get() = type == DocumentsContract.Document.MIME_TYPE_DIR
    val signature get() = "$size:$modified"
  }

  fun document(uri: Uri): Document {
    resolver.query(uri, null, null, null, null)?.use { cursor ->
      check(cursor.moveToFirst()) { "The selected file is unavailable." }
      fun text(column: String, fallback: String = ""): String {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else fallback
      }
      fun number(column: String, fallback: Long = 0): Long {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else fallback
      }
      return Document(uri, text(OpenableColumns.DISPLAY_NAME, "File"), text(DocumentsContract.Document.COLUMN_MIME_TYPE),
        number(OpenableColumns.SIZE, -1), number(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
    }
    error("The selected document is unavailable.")
  }

  fun root(uri: Uri): Uri = if (DocumentsContract.isTreeUri(uri)) {
    DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
  } else uri

  private fun children(uri: Uri): List<Document> {
    val result = mutableListOf<Document>()
    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    resolver.query(childUri, columns, null, null, null)?.use { cursor ->
      while (cursor.moveToNext()) {
        result.add(Document(DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)),
          cursor.getString(1), cursor.getString(2), if (cursor.isNull(3)) -1 else cursor.getLong(3), cursor.getLong(4)))
      }
    }
    return result
  }

  fun selection(token: String): Map<String, Any> {
    val doc = document(root(Uri.parse(token)))
    return mapOf("uri" to token, "name" to doc.name, "folder" to doc.folder, "size" to doc.size)
  }

  override fun scan(selected: List<String>): List<EtherDropEngine.Source> {
    val result = mutableListOf<EtherDropEngine.Source>()
    val names = mutableSetOf<String>()
    fun visit(doc: Document, path: String) {
      EtherDropEngine.safePath(path)
      if (doc.folder) children(doc.uri).sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { visit(it, "$path/${it.name}") }
      else result.add(EtherDropEngine.Source(doc.uri.toString(), path, doc.size, doc.signature))
    }
    selected.distinct().forEach { token ->
      val doc = document(root(Uri.parse(token)))
      var name = doc.name
      var suffix = 2
      while (!names.add(name.lowercase(Locale.ROOT))) name = "${doc.name}_${suffix++}"
      visit(doc, name)
    }
    return result
  }
  override fun read(source: EtherDropEngine.Source): InputStream =
    checkNotNull(resolver.openInputStream(Uri.parse(source.token))) { "Cannot read ${source.path}." }
  override fun signature(source: EtherDropEngine.Source): String = document(Uri.parse(source.token)).signature

  override fun destination(token: String, peer: String, wrap: Boolean): EtherDropEngine.Target = Output(root(Uri.parse(token)), peer, wrap)

  private inner class Output(parent: Uri, peer: String, wrap: Boolean) : EtherDropEngine.Target {
    private val created = mutableListOf<Uri>()
    private val files = mutableMapOf<String, Uri?>()
    private val directories = mutableMapOf<String, Uri>()
    private val directoryEntries = mutableMapOf<Uri, MutableMap<String, Document>>()
    private val base: Uri
    private val display: String
    init {
      val parentName = document(parent).name
      if (wrap) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val name = "EtherDrop_${peer.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}_$stamp"
        base = create(parent, DocumentsContract.Document.MIME_TYPE_DIR, uniqueName(parent, name))
        display = "$parentName/${document(base).name}"
      } else { base = parent; display = parentName }
      directories[""] = base
    }
    override fun location(): String = display
    override fun totalSpace(): Long = StatFs(context.filesDir.absolutePath).totalBytes
    override fun freeSpace(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    private fun entries(parent: Uri): MutableMap<String, Document> = directoryEntries.getOrPut(parent) {
      children(parent).associateBy { it.name }.toMutableMap()
    }
    private fun find(parent: Uri, name: String): Document? = entries(parent)[name]
    private fun uniqueName(parent: Uri, name: String): String {
      val names = entries(parent).keys.map { it.lowercase(Locale.ROOT) }.toSet()
      var result = name
      var suffix = 2
      val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
      while (result.lowercase(Locale.ROOT) in names) result = "${name.take(dot)} (${suffix++})${name.drop(dot)}"
      return result
    }
    private fun create(parent: Uri, type: String, name: String): Uri {
      val uri = checkNotNull(DocumentsContract.createDocument(resolver, parent, type, name)) { "Cannot create $name." }
      created.add(uri)
      val doc = document(uri)
      entries(parent)[doc.name] = doc
      return uri
    }
    private fun directory(path: String): Uri {
      directories[path]?.let { return it }
      val parentPath = path.substringBeforeLast('/', "")
      val name = path.substringAfterLast('/')
      val parent = directory(parentPath)
      val existing = find(parent, name)
      check(existing == null || existing.folder) { "A file already occupies the folder $path." }
      val uri = existing?.uri ?: create(parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
      directories[path] = uri
      return uri
    }
    override fun write(path: String, offset: Long): OutputStream {
      EtherDropEngine.safePath(path)
      if (!files.containsKey(path)) {
        val parent = directory(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val existing = find(parent, name)
        val uri = if (existing == null) create(parent, "application/octet-stream", name) else {
          check(!existing.folder) { "A folder already occupies $path." }
          when (conflict(path)) {
            "replace" -> existing.uri
            "skip" -> null
            else -> create(parent, "application/octet-stream", uniqueName(parent, name))
          }
        }
        files[path] = uri
      }
      val uri = files[path] ?: return object : OutputStream() {
        override fun write(value: Int) {}
        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
      }
      val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "rw")) { "Cannot write $path." }
      try {
        val stream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        stream.channel.truncate(offset)
        stream.channel.position(offset)
        return stream
      } catch (error: Exception) { descriptor.close(); throw error }
    }
    override fun signature(path: String): String = files[path]?.let { document(it).signature } ?: "Skipped"
    override fun cancel() {
      for (uri in created.asReversed()) check(DocumentsContract.deleteDocument(resolver, uri)) { "Could not remove ${uri.lastPathSegment}." }
      created.clear()
    }
  }
}
