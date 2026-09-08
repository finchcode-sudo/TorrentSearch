package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.domain.model.TorrentDetails
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/**
 * 磁力狗 (clg62.top / ciligou.net) — 无分类磁力搜索引擎。
 *
 * 该站上一次搜索前要先 POST 一次 `act=challenge` 以建立服务端 session，
 * 之后通过 `GET /search?word=<base64(关键词)>&sort=time` 取得结果列表。
 * 列表页本身不含磁力链，磁力只出现在每条结果的 `/information/<hash>` 详情页中，
 * 因此这里会对列表项并发请求详情页补齐磁力与 info hash。
 */
class Cligou(private val networkClient: NetworkClient) :
    SearchProvider,
    TorrentDetailsProvider {

    override val id = "cligou"
    override val name = "磁力狗"
    override val url = "https://clg62.top"
    override val supportedCategories = setOf(Category.Other)
    override val safetyStatus = SearchProviderSafetyStatus.Safe
    override val enabledByDefault = false

    override suspend fun search(query: String, category: Category): List<Torrent> {
        establishSession()

        val word = Base64.getEncoder().encodeToString(query.toByteArray(Charsets.UTF_8))
        val responseHtml = networkClient.getText("$url/search?word=$word&sort=time")
        if (responseHtml.contains(VERIFICATION_MARKER)) return emptyList()

        val items = parseListItems(responseHtml, url)
        val results = mutableListOf<Torrent>()
        val limiter = Semaphore(6)

        coroutineScope {
            for (item in items) {
                launch {
                    limiter.withPermit {
                        val torrent = item.buildTorrent(this@Cligou.name)
                        if (torrent != null) {
                            synchronized(results) { results.add(torrent) }
                        }
                    }
                }
            }
        }
        return results
    }

    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        val html = networkClient.getText(detailsPageUrl)
        return parseDetails(detailsPageUrl, html)
    }

    private suspend fun establishSession() {
        runCatching { networkClient.getText("$url/?from=bs5.org") }
        runCatching {
            networkClient.submitForm("$url/?from=bs5.org", mapOf("act" to "challenge"))
        }
    }

    private fun parseListItems(html: String, pageUrl: String): List<ListItem> =
        Jsoup.parse(html, pageUrl)
            .select(LIST_ITEM_SELECTOR)
            .mapNotNull { li -> li.toListItem() }

    private fun Element.toListItem(): ListItem? {
        val titleEl = selectFirst(RESULT_TITLE_SELECTOR) ?: return null
        val title = titleEl.text().trim().ifEmpty { return null }
        val detailsUrl = titleEl.absUrl("href")
        if (detailsUrl.isBlank()) return null

        val size = selectFirst(SIZE_INFO)?.text()
        val dateText = selectFirst(DATE_INFO)?.text()
        return ListItem(
            name = title,
            size = size?.takeIf { it.isNotBlank() },
            uploadDate = dateText?.toUploadDate(),
            detailsPageUrl = detailsUrl,
        )
    }

    /** 并发抓取某一大项的详情页，取得磁力链与 info hash 后组装成 [Torrent]。 */
    private suspend fun ListItem.buildTorrent(providerName: String): Torrent? {
        val html = runCatching { networkClient.getText(detailsPageUrl) }.getOrNull()
            ?: return null
        val details = parseDetails(detailsPageUrl, html) ?: return null

        return Torrent(
            infoHash = details.infoHash,
            name = details.name,
            size = details.size ?: size,
            seeders = null,
            peers = null,
            providerName = providerName,
            uploadDate = details.uploadDate ?: uploadDate,
            category = Category.Other,
            descriptionPageUrl = detailsPageUrl,
            magnetUri = details.magnetUri,
            fileDownloadLink = null,
        )
    }

    private fun parseDetails(ignoredPageUrl: String, html: String): TorrentDetails? {
        val doc = Jsoup.parse(html)
        val magnetEl = doc.selectFirst(MAGNET_SELECTOR) ?: return null
        val magnetUri = magnetEl.attr("href")
        if (!magnetUri.startsWith("magnet:?xt=urn:btih:")) return null

        val infoHash = TorrentUtils.getInfoHashFromMagnetUri(magnetUri)
        val title = doc.selectFirst(DETAIL_TITLE_SELECTOR)?.text()?.trim()
            ?: doc.title().trim()
        val sizeText = doc.selectFirst(SIZE_INFO)?.text()
        val dateText = doc.selectFirst(DATE_INFO)?.text()

        return TorrentDetails(
            infoHash = infoHash,
            name = title,
            size = sizeText?.takeIf { it.isNotBlank() },
            uploadDate = dateText?.toUploadDate(),
            category = Category.Other,
            magnetUri = magnetUri,
        )
    }

    private fun String.toUploadDate(): Instant? =
        Regex("""(\d{4}-\d{2}-\d{2})""").find(this)
            ?.groupValues
            ?.get(1)
            ?.let { dateStr ->
                runCatching {
                    LocalDate.parse(dateStr).atStartOfDay().toInstant(ZoneOffset.UTC)
                }.getOrNull()
            }

    private data class ListItem(
        val name: String,
        val size: String?,
        val uploadDate: Instant?,
        val detailsPageUrl: String,
    )

    private companion object {
        const val VERIFICATION_MARKER = "Verification Page"
        const val LIST_ITEM_SELECTOR = "#Search_list_wrapper > li"
        const val RESULT_TITLE_SELECTOR = "a.SearchListTitle_result_title"
        const val SIZE_INFO = "em:containsOwn(文件大小)"
        const val DATE_INFO = "em:containsOwn(创建时间)"
        const val MAGNET_SELECTOR = """a[href^="magnet:"]"""
        const val DETAIL_TITLE_SELECTOR = "h1, [class*=Information_]title, .Information_title"
    }
}
