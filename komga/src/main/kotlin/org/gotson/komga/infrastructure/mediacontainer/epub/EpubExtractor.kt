package org.gotson.komga.infrastructure.mediacontainer.epub

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.EntryNotFoundException
import org.gotson.komga.domain.model.EpubTocEntry
import org.gotson.komga.domain.model.MediaFile
import org.gotson.komga.domain.model.R2Locator
import org.gotson.komga.domain.model.TypedBytes
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import java.net.URLDecoder
import java.nio.file.Path
import java.util.Objects
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.math.ceil
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

@Service
class EpubExtractor(
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
  @Value("#{@komgaProperties.epubDivinaLetterCountThreshold}") private val letterCountThreshold: Int,
) {
  /**
   * Retrieves a specific entry by name from the zip archive
   */
  fun getEntryStream(
    path: Path,
    entryName: String,
  ): ByteArray =
    ZipFile(path.toFile()).use { zip ->
      val bytes: ByteArray?
      try {
        var entry = zip.getEntry(entryName)
        var inputStream = zip.getInputStream(entry)
        if (Objects.isNull(inputStream)) {
          entry = zip.getEntry(URLDecoder.decode(entryName, "UTF-8"))
          inputStream = zip.getInputStream(entry)
        }
        bytes = inputStream.use { it.readAllBytes() }
      } catch (e: Exception) {
        throw EntryNotFoundException("Entry does not exist: $entryName")
      }
      bytes
    }

  fun isEpub(path: Path): Boolean =
    true

  /**
   * Retrieves the book cover along with its mediaType from the epub 2/3 manifest
   */
  fun getCover(path: Path): TypedBytes? {
    val bytes = path.epub { (zip, opfDoc, opfDir, manifest) ->
      try {
        val coverManifestItem =
          // EPUB 3 - try to get cover from manifest properties 'cover-image'
          manifest.values.firstOrNull { it.properties.contains("cover-image") || it.id == "cover.xhtml" }
            ?: // EPUB 2 - get cover from meta element with name="cover"
            opfDoc.selectFirst("metadata > meta[name=cover]")?.attr("content")?.ifBlank { null }?.let { manifest[it] }
        var href = coverManifestItem!!.href
        var mediaType = coverManifestItem.mediaType

        if (coverManifestItem.id == "cover.xhtml") {
          val cover = zip.getInputStream(zip.getEntry(normalizeHref(opfDir, href)))
            .use { Jsoup.parse(it, null, "") }
          val img = cover.getElementsByTag("img").ifEmpty { cover.getElementsByTag("image") }
          href = img.attr("src").ifEmpty { img.attr("xlink:href") }
          href = normalizeHref(opfDir, href)
          mediaType = "image/"
        }
        val coverPath = URLDecoder.decode(normalizeHref(opfDir, href), "UTF-8")
        TypedBytes(
          zip.getInputStream(zip.getEntry(coverPath)).readAllBytes(),
          mediaType,
        )
      } catch (e: Exception) {
        null
      }
    }

    if (Objects.nonNull(bytes)) {
      return bytes
    }

    // 先判断有没有 cover
    val zip = ZipFile(path.toFile())
    val entries = zip.entries.toList()
      .filter { item ->
        listOf(".jpg", ".png", ".jpeg").any { item.name.endsWith(it) }
      }
    for (entry in entries) {
      for (s in listOf("cover.jpg", "cover.png", "cover.jpeg")) {
        if (entry.name.endsWith(s)) {
          return TypedBytes(
            zip.getInputStream(entry).readAllBytes(),
            "image/",
          )
        }
      }
    }

    if (Objects.nonNull(entries.firstOrNull())) {
      val inputStream = zip.getInputStream(entries.firstOrNull())
      if (Objects.isNull(inputStream)) {
        return null
      }
      // 没有找到 cover 直接使用第一个图片
      return TypedBytes(
        inputStream.readAllBytes(),
        "image/",
      )
    }

    return null
  }

  fun getManifest(
    path: Path,
    analyzeDimensions: Boolean,
  ): EpubManifest =
    path.epub { epub ->
      val (resources, missingResources) = getResources(epub).partition { it.fileSize != null }
      val isFixedLayout = isFixedLayout(epub)
      val pageCount = computePageCount(epub)
      EpubManifest(
        resources = resources,
        missingResources = missingResources,
        toc = getToc(epub),
        landmarks = getLandmarks(epub),
        pageList = getPageList(epub),
        pageCount = pageCount,
        isFixedLayout = isFixedLayout,
        positions = computePositions(resources, isFixedLayout),
        divinaPages = getDivinaPages(epub, isFixedLayout, pageCount, analyzeDimensions),
      )
    }

  private fun getResources(epub: EpubPackage): List<MediaFile> {
    val spine = epub.opfDoc.select("spine > itemref").map { it.attr("idref") }.mapNotNull { epub.manifest[it] }

    val pages =
      spine.map { page ->
        MediaFile(
          normalizeHref(epub.opfDir, URLDecoder.decode(page.href, Charsets.UTF_8)),
          page.mediaType,
          MediaFile.SubType.EPUB_PAGE,
        )
      }

    val assets =
      epub.manifest.values.filterNot { spine.contains(it) }.map {
        MediaFile(
          normalizeHref(epub.opfDir, URLDecoder.decode(it.href, Charsets.UTF_8)),
          it.mediaType,
          MediaFile.SubType.EPUB_ASSET,
        )
      }

    val zipEntries = epub.zip.entries.toList()
    return (pages + assets).map { resource ->
      resource.copy(
        fileSize =
        zipEntries.firstOrNull {
          listOf(resource.fileName, URLDecoder.decode(resource.fileName, "UTF-8"))
            .contains(resource.fileName)
        }
          ?.let { if (it.size == ArchiveEntry.SIZE_UNKNOWN) null else it.size },
      )
    }
  }

  private fun getDivinaPages(
    epub: EpubPackage,
    isFixedLayout: Boolean,
    pageCount: Int,
    analyzeDimensions: Boolean,
  ): List<BookPage> {
    if (!isFixedLayout) return emptyList()

    try {
      val pagesWithImages =
        epub.opfDoc.select("spine > itemref")
          .map { it.attr("idref") }
          .mapNotNull { idref -> epub.manifest[idref]?.href?.let { normalizeHref(epub.opfDir, it) } }
          .map { pagePath ->
            var inputStream = epub.zip.getInputStream(epub.zip.getEntry(pagePath))
            if (Objects.isNull(inputStream)) {
              inputStream = epub.zip.getInputStream(epub.zip.getEntry(URLDecoder.decode(pagePath, "UTF-8")))
            }

            val doc = inputStream.use { Jsoup.parse(it, null, "") }

            // if a page has text over the threshold then the book is not divina compatible
            if (doc.body().text().length > letterCountThreshold) return emptyList()

            val img =
              doc.getElementsByTag("img")
                .map { it.attr("src") } // get the src, which can be a relative path

            val svg =
              doc.select("svg > image[xlink:href]")
                .map { it.attr("xlink:href") } // get the source, which can be a relative path

            (img + svg).map { (Path(pagePath).parent ?: Path("")).resolve(it).normalize().invariantSeparatorsPathString } // resolve it against the page folder
          }

      if (pagesWithImages.size != pageCount) return emptyList()
      val imagesPath = pagesWithImages.flatten()
      if (imagesPath.size != pageCount) return emptyList()

      val divinaPages =
        imagesPath.mapNotNull { imagePath ->
          val mediaType = epub.manifest.values.firstOrNull { normalizeHref(epub.opfDir, it.href) == imagePath }?.mediaType ?: return@mapNotNull null
          var zipEntry = epub.zip.getEntry(URLDecoder.decode(imagePath, "UTF-8"))
          if (!contentDetector.isImage(mediaType)) return@mapNotNull null

          val dimension =
            if (analyzeDimensions) {
              var inputStream = epub.zip.getInputStream(zipEntry)
              if (Objects.isNull(inputStream)) {
                zipEntry = epub.zip.getEntry(URLDecoder.decode(imagePath, "UTF-8"))
                inputStream = epub.zip.getInputStream(zipEntry)
              }
              inputStream.use { imageAnalyzer.getDimension(it) }
            } else
              null
          val fileSize = if (zipEntry.size == ArchiveEntry.SIZE_UNKNOWN) null else zipEntry.size
          BookPage(fileName = URLDecoder.decode(imagePath, "UTF-8"), mediaType = mediaType, dimension = dimension, fileSize = fileSize)
        }

      if (divinaPages.size != pageCount) return emptyList()
      return divinaPages
    } catch (e: Exception) {
      logger.warn(e) { "Error while getting divina pages" }
      return emptyList()
    }
  }

  private fun computePageCount(epub: EpubPackage): Int {
    val spine =
      epub.opfDoc.select("spine > itemref")
        .map { it.attr("idref") }
        .mapNotNull { idref -> epub.manifest[idref]?.href?.let { normalizeHref(epub.opfDir, it) } }

    return epub.zip.entries.toList().filter { it.name in spine }.sumOf { ceil(it.compressedSize / 1024.0).toInt() }
  }

  private fun isFixedLayout(epub: EpubPackage) =
    epub.opfDoc.selectFirst("metadata > *|meta[property=rendition:layout]")?.text() == "pre-paginated" ||
      epub.opfDoc.selectFirst("metadata > *|meta[name=fixed-layout]")?.attr("content") == "true"

  private fun computePositions(
    resources: List<MediaFile>,
    isFixedLayout: Boolean,
  ): List<R2Locator> {
    val readingOrder = resources.filter { it.subType == MediaFile.SubType.EPUB_PAGE }

    var startPosition = 1
    val positions =
      if (isFixedLayout) {
        readingOrder.map {
          R2Locator(
            href = it.fileName,
            type = it.mediaType ?: "application/octet-stream",
            locations = R2Locator.Location(progression = 0F, position = startPosition++),
          )
        }
      } else {
        readingOrder.flatMap { file ->
          val positionCount = maxOf(1, ceil((file.fileSize ?: 0) / 1024.0).roundToInt())
          (0 until positionCount).map { p ->
            R2Locator(
              href = file.fileName,
              type = file.mediaType ?: "application/octet-stream",
              locations = R2Locator.Location(progression = p.toFloat() / positionCount, position = startPosition++),
            )
          }
        }
      }

    return positions.map { locator ->
      val totalProgression = locator.locations?.position?.let { it.toFloat() / positions.size }
      locator.copy(locations = locator.locations?.copy(totalProgression = totalProgression))
    }
  }

  private fun getToc(epub: EpubPackage): List<EpubTocEntry> {
    // Epub 3
    epub.getNavResource()?.let { return processNav(it, Epub3Nav.TOC) }
    // Epub 2
    epub.getNcxResource()?.let { return processNcx(it, Epub2Nav.TOC) }
    return emptyList()
  }

  private fun getPageList(epub: EpubPackage): List<EpubTocEntry> {
    // Epub 3
    epub.getNavResource()?.let { return processNav(it, Epub3Nav.PAGELIST) }
    // Epub 2
    epub.getNcxResource()?.let { return processNcx(it, Epub2Nav.PAGELIST) }
    return emptyList()
  }

  private fun getLandmarks(epub: EpubPackage): List<EpubTocEntry> {
    // Epub 3
    epub.getNavResource()?.let { return processNav(it, Epub3Nav.LANDMARKS) }

    // Epub 2
    return processOpfGuide(epub.opfDoc, epub.opfDir)
  }
}
