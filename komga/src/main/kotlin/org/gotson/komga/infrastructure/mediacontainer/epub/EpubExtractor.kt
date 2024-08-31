package org.gotson.komga.infrastructure.mediacontainer.epub

import cn.hutool.core.img.FontUtil
import cn.hutool.core.io.FileUtil
import cn.hutool.core.io.IoUtil
import cn.hutool.core.io.file.FileNameUtil
import cn.hutool.extra.spring.SpringUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.EpubTocEntry
import org.gotson.komga.domain.model.MediaFile
import org.gotson.komga.domain.model.R2Locator
import org.gotson.komga.domain.model.TypedBytes
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.mediacontainer.ExtractorUtil
import org.gotson.komga.infrastructure.util.getZipEntryBytes
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.file.Path
import java.util.Objects
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
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
  ): ByteArray = getZipEntryBytes(path, entryName)

  fun isEpub(path: Path): Boolean =
    true

  /**
   * Retrieves the book cover along with its mediaType from the epub 2/3 manifest
   */
  fun getCover(path: Path): TypedBytes? {
    path.epub { (zip, opfDoc, opfDir, manifest) ->
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
        val bufferedImage = zip.getInputStream(zip.getEntry(coverPath)).use { ImageIO.read(it) }
        if (!ExtractorUtil.getImageColorPercentage(bufferedImage)) {
          return TypedBytes(
            zip.getInputStream(zip.getEntry(coverPath)).use { it.readAllBytes() },
            mediaType,
          )
        }
      } catch (_: Exception) {
      }
    }

    // 先判断有没有 cover
    val zip = ZipFile(path.toFile())
    val entries = zip.entries.toList()
      .filter(Objects::nonNull)
      .filter { item ->
        listOf(".jpg", ".png", ".jpeg").any { item.name.endsWith(it) }
      }
      .filter {
        val bufferedImage = zip.getInputStream(it).use { it1 -> ImageIO.read(it1) }
        Objects.nonNull(bufferedImage)
      }
    for (entry in entries) {
      for (s in listOf("cover.jpg", "cover.png", "cover.jpeg")) {
        if (!entry.name.endsWith(s)) {
          continue
        }
        val bufferedImage = zip.getInputStream(entry).use { ImageIO.read(it) }
        if (Objects.isNull(bufferedImage)) {
          continue
        }
        if (ExtractorUtil.getImageColorPercentage(bufferedImage)) {
          continue
        }
        return TypedBytes(
          zip.getInputStream(entry).use { it.readAllBytes() },
          "image/",
        )
      }
    }

    if (Objects.isNull(entries.firstOrNull())) {
      return generateCover(path.name)
    }

    val bufferedImage = zip.getInputStream(entries.firstOrNull()).use { ImageIO.read(it) }
    if (Objects.isNull(bufferedImage) || ExtractorUtil.getImageColorPercentage(bufferedImage)) {
      return generateCover(path.name)
    }
    // 没有找到 cover 直接使用第一个图片
    return TypedBytes(
      zip.getInputStream(entries.firstOrNull()).use { it.readAllBytes() },
      "image/",
    )
  }

  /**
   * 生成文字封面
   */
  fun generateCover(s: String): TypedBytes? {
    var name = FileNameUtil.mainName(s)
    if (name.length > 24) {
      name = name.substring(0, 22) + "..."
    }

    val width = 2120
    val height = 3000
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.graphics
    graphics.color = Color.WHITE;
    graphics.fillRect(0, 0, width, height);

    var font: Font? = null
    var inputStream: InputStream? = null
    try {

      val applicationContext = SpringUtil.getApplicationContext()
      val resource = applicationContext
        .getResource("classpath:font/simhei.ttf")
      inputStream = resource.inputStream
      font = FontUtil.createFont(inputStream.use { ByteArrayInputStream(it.readAllBytes()) })
      logger.info { "classpath:font/simhei.ttf  ok" }
    } catch (e: Exception) {
      logger.error { e }
    } finally {
      IoUtil.close(inputStream)
    }

    if (Objects.nonNull(font)) {
      graphics.font = font?.deriveFont(240f)
    }
    graphics.color = Color.BLACK

    val fontMetrics = graphics.fontMetrics

    var sb = ""
    var i = 0;

    val split = name.split("")
    for ((index, s) in split.withIndex()) {
      val ss = sb + s
      var stringWidth = fontMetrics.stringWidth(ss)
      if (stringWidth < width && split.size > index + 1) {
        sb = ss
        continue
      }
      stringWidth = fontMetrics.stringWidth(sb)
      graphics.drawString(sb, (width - stringWidth) / 2, (height * 0.25).roundToInt() + (i * fontMetrics.height))
      sb = s
      i++
    }
    graphics.dispose()

    ByteArrayOutputStream()
      .use {
        ImageIO.write(image, "png", it)
        it.flush()
        val typedBytes = TypedBytes(
          it.toByteArray(),
          "image/",
        )
        FileUtil.writeBytes(typedBytes.bytes, File("D://test.jpg"))
        return typedBytes
      }
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
