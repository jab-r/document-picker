package com.reactnativedocumentpicker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.FileUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.util.RNLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class FileOperations(private val uriMap: MutableMap<String, Uri>) {
  suspend fun copyFilesToLocalStorage(
    context: ReactContext,
    filesToCopy: ReadableArray,
    copyTo: CopyDestination,
  ): ReadableArray =
    withContext(Dispatchers.IO) {
      /**
       * export type LocalCopyResponse = | { status: 'success'; localUri: string; sourceUri: string } |
       * { status: 'error'; copyError: string; sourceUri: string }
       */
      val destinationDir = getUniqueDir(context, copyTo)

      val copyJobs = (0 until filesToCopy.size()).map { i ->
        async {
          val oneResult = Arguments.createMap()
          val map = filesToCopy.getMap(i)

          try {
            val nonNullMap = map ?: throw IllegalArgumentException("keepLocalCopy: The file argument is null at index $i")
            val newFile = copySingleFile(nonNullMap, context, destinationDir)
            oneResult.merge(newFile)
          } catch (e: Exception) {
            val message: String = e.localizedMessage ?: e.message ?: "Unknown error"
            oneResult.putString("status", "error")
            oneResult.putString("copyError", message)
            oneResult.putString("sourceUri", map?.getString("uri"))
          }
          return@async oneResult
        }
      }

      val results = Arguments.createArray()
      copyJobs.awaitAll().forEach { result ->
        results.pushMap(result)
      }

      return@withContext results
    }

  private fun copySingleFile(
    map: ReadableMap,
    context: ReactContext,
    destinationDir: File
  ): ReadableMap {
    val sourceUriAsString: String = map.getString("uri") ?: throw IllegalArgumentException("URI is missing")
    val fileName: String = map.getString("fileName") ?: throw IllegalArgumentException("fileName is missing")
    val convertVirtualFileAsType = map.getString("convertVirtualFileToType")

    val sourceUriInstance = uriMap[sourceUriAsString]
    if (sourceUriInstance == null) {
      RNLog.w(
        context,
        // https://developer.android.com/guide/components/intents-common#GetFile
        "keepLocalCopy: You're trying to copy a file \"$fileName\" that wasn't picked with this module. " +
          "This can lead to permission errors because the file reference is transient to your activity's current lifecycle. See https://developer.android.com/guide/components/intents-common#GetFile . " +
          "Please use the result from the picker directly.")
    }

    val copiedFile =
      copyFile(
        context,
        sourceUriInstance ?: Uri.parse(sourceUriAsString),
        destinationDir,
        fileName,
        convertVirtualFileAsType)

    val singleFileCopy = Arguments.createMap()
    singleFileCopy.putString("status", "success")
    // NOTE this url-encodes the path, consistent with the iOS implementation and with the response of not-copied files
    singleFileCopy.putString("localUri", Uri.fromFile(copiedFile).toString())
    singleFileCopy.putString("sourceUri", sourceUriAsString)
    return singleFileCopy
  }

  private fun getUniqueDir(context: Context, copyTo: CopyDestination): File {
    val baseDir =
      if (copyTo == CopyDestination.DOCUMENT_DIRECTORY) context.filesDir else context.cacheDir

    val randomDir = File(baseDir, UUID.randomUUID().toString())
    val didCreateDir = randomDir.mkdir()
    if (!didCreateDir) {
      throw IOException("Failed to create directory at ${randomDir.absolutePath}")
    }
    return randomDir
  }

  private fun copyFile(
    context: Context,
    from: Uri,
    destinationDir: File,
    fileName: String,
    convertVirtualFileAsType: String?
  ): File {
    val attemptedDestFile = File(destinationDir, fileName)
    val destFileSafe = safeGetDestination(attemptedDestFile, destinationDir)

    val copyStreamToFile: (InputStream?) -> Unit = { inputStream ->
      inputStream ?: throw FileNotFoundException("No input stream was found for the source file")
      val bytesCopied = copyStreamToAnother(inputStream, FileOutputStream(destFileSafe))
      if (bytesCopied == 0L) {
        throw IOException("No data was copied to the destination file")
      }
    }

    if (convertVirtualFileAsType == null) {
      copyStreamToFile(context.contentResolver.openInputStream(from))
    } else {
      copyStreamToFile(getInputStreamForVirtualFile(context.contentResolver, from, convertVirtualFileAsType))
    }

    return destFileSafe
  }

  private fun getInputStreamForVirtualFile(
    contentResolver: ContentResolver,
    from: Uri,
    convertVirtualFileAsType: String
  ): InputStream? {
    return contentResolver
      .openTypedAssetFileDescriptor(from, convertVirtualFileAsType, null)
      ?.createInputStream()
  }

  private fun safeGetDestination(destFile: File, expectedDir: File): File {
    val canonicalPath = destFile.canonicalPath
    if (!canonicalPath.startsWith(expectedDir.canonicalPath)) {
      throw IllegalArgumentException(
        "The copied file is attempting to write outside of the target directory.")
    }
    return destFile
  }

  fun writeDocumentImpl(sourceUri: Uri?, targetUriString: String?, context: ReactApplicationContext): DocumentMetadataBuilder {
    sourceUri ?:  throw IllegalArgumentException("The source URI is null. Call saveDocument() before writeDocument()")
    val targetUri: Uri? = uriMap[targetUriString]

    if (targetUri == null) {
      RNLog.e(
        context,
        "writeDocument: You're trying to write from Uri \"$targetUriString\" that wasn't picked with this module. " +
          "Please use the result from saveDocument()")
      throw IllegalArgumentException("The provided URI is not known")
    }

    val metadataBuilder = DocumentMetadataBuilder(targetUri)

    val contentResolver = context.contentResolver
    val mimeFromUri = contentResolver.getType(targetUri)
    metadataBuilder.mimeType(mimeFromUri)

    val inputStream = contentResolver.openInputStream(sourceUri)
      ?: return metadataBuilder.metadataReadingError("No input stream found for source file")

    val outputStream = contentResolver.openOutputStream(targetUri)
      ?: return metadataBuilder.metadataReadingError("No output stream found for destination file")

    val bytesCopied = copyStreamToAnother(inputStream, outputStream)
    if (bytesCopied == 0L) {
      metadataBuilder.metadataReadingError("No data was copied to the destination file")
    }

    return metadataBuilder
  }

  val copyStreamToAnother: (InputStream, OutputStream) -> Long = { inputStream, outputStream ->
    inputStream.use { input ->
      outputStream.use { output ->
        val bytesCopied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          FileUtils.copy(inputStream, outputStream)
        } else {
          inputStream.copyTo(outputStream)
        }
        return@use bytesCopied
      }
    }
  }
}
