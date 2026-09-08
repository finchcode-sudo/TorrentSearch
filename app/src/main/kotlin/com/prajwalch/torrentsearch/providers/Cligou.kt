package com.prajwalch.torrentsearch.providers

import com.prajwalch.torrentsearch.domain.model.Category
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.domain.model.TorrentDetails
import com.prajwalch.torrentsearch.network.NetworkClient
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/**
 * 磁力狗 (clg62.top / ciligou.net) — 无分类磁力搜索引擎。
 *
 * 该站访问需先提交一次 `act=challenge` 以建立服务端 session，
 * 之后通过 `GET /search?word=<base64(关键词)>&sort=time` 返回结果列表。
 * 列表页本身不含磁力链，磁力只在每条结果的 `/information/<hash>` 详情页中，
 * 因此这里会并发请求详情页以补齐磁力。
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
        val requestUrl = "$url/search?word=$word&sort=time"
        val responseHtml = networkClient.getText(requestUrl)
        // 若仍未取得有效 session，会返回验证壳页面，不是结果列表。
        if (responseHtml.contains(VERIFICATION_MARKER)) return emptyList()

        val items = withContext(Dispatchers.Default) {
            parseListItems(responseHtml, requestUrl)
        }

        val results = mutableListOf<Torrent>()
        val limiter = Semaphore(6)
        coroutineScope {
            for (item in items) {
                launch {
                    limiter.withPermit {
                        val torrent = item.magnetize()
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
        return parseDetailsPage(html, detailsPageUrl)
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

        val infoEl = selectFirst(INFO_SELECTOR)
        return ListItem(
            name = title,
            size = infoEl?.getLabeledValue(LABEL_SIZE),
            uploadDate = infoEl?.getLabeledValue(LABEL_DATE)?.toInstantOrNull(),
            detailsPageUrl = detailsUrl,
        )
    }

    private fun Element.getLabeledValue(label: String): String? {
        val labelEm = children().firstOrNull {
            it.tagName() == "em" && it.ownText().contains(label)
        } ?: return null
        val builder = StringBuilder()
        var next = labelEm.nextElementSibling()
        while (next != null && !(next.tagName() == "em" && next != labelEm)) {
            builder.append(next.text()).append(' ')
            next = next.nextElementSibling() ?: break
        }
        return builder.toString().trim().ifEmpty { null }
    }

    private fun parseDetailsPage(html: String, pageUrl: String): TorrentDetails? =
        withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html, pageUrl)
            val magnetEl = doc.selectFirst(MAGNET_SELECTOR) ?: return@withContext null
            val magnetUri = magnetEl.attr("href")
            if (!magnetUri.startsWith("magnet:?xt=urn:btih:")) return@withContext null
            val infoHash = TorrentUtils.getInfoHashFromMagnetUri(magnetUri)
            val title = doc.selectFirst(DETAIL_TITLE_SELECTOR)?.text()?.trim()
                ?: doc.title().trim()
            val bodyText = doc.body()?.text().orEmpty()

            TorrentDetails(
                infoHash = infoHash,
                name = title,
                size = extractSize(bodyText),
                magnetUri = magnetUri,
                category = Category.Other,
            )
        }

    private suspend fun ListItem.magnetize(): Torrent? {
        if (detailsPageUrl.isBlank()) return null
        val html = runCatching { networkClient.getText(detailsPageUrl) }.getOrNull()
            ?: return null
        val details = parseDetailsPage(html, detailsPageUrl) ?: return null
        val magnet = details.magnetUri ?: return null

        return Torrent(
            infoHash = TorrentUtils.getInfoHashFromMagnetUri(magnet),
            name = details.name,
            size = details.size ?: size,
            providerName = Cligou.this.name,
            uploadDate = details.uploadDate ?: uploadDate,
            category = Category.Other,
            descriptionPageUrl = detailsPageUrl,
            magnetUri = magnet,
        )
    }

    private fun extractSize(bodyText: String): String? {
        val sizeRegex = Regex("""(\d+(?:\.\d+)?)\s*(T|G|M|K)?[i]?B""", RegexOption.IGNORE_CASE)
        return sizeRegex.findAll(bodyText)
            .mapNotNull { m ->
                val unit = m.groupValues[2].uppercase()
                val unitNorm = normalizeUnit(unit) ?: return@mapNotNull null
                "${m.groupValues[1]} $unitNorm"
            }
            .maxByOrNull {
                val num = Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() ?: 0.0
                val u = it.substringAfter(' ', "").take(1)
                num * when (u) {
                    "T" -> 1e3
                    "M" -> 1e-3
                    "K" -> 1e-6
                    else -> 1.0
                }
            }
    }

    private fun normalizeUnit(raw: String): String? = when (raw.uppercase()) {
        "TB", "TIB" -> "TB"
        "GB", "GIB" -> "GB"
        "MB", "MIB" -> "MB"
        "KB", "KIB" -> "KB"
        else -> null
    }

    private fun String.toInstantOrNull(): Instant? =
        Regex("""(\d{4}-\d{2}-\d{2})""").find(this)
            ?.groupValues?.get(1)
            ?.let {
                runCatching {
                    LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC)
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
        const val INFO_SELECTOR = ".Search_list_info"
        const val LABEL_SIZE = "文件大小"
        const val LABEL_DATE = "创建时间"
        const val MAGNET_SELECTOR = """a[href^="magnet:"]"""
        const val DETAIL_TITLE_SELECTOR = "h1"
    }
}
