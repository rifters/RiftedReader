package com.rifters.riftedreader.pagination

import com.rifters.riftedreader.domain.pagination.ChapterIndex
import com.rifters.riftedreader.domain.pagination.WindowIndex

/**
 * FlexPaginator - Minimal window assembler using flex layout.
 *
 * Responsibilities:
 * - Get 5 chapters for window range
 * - Wrap in <section data-chapter="N"> tags
 * - Return HTML string to WebView
 *
 * NOT Responsible For:
 * - Chapter streaming (Conveyor handles this)
 * - Pagination logic (flex_paginator.js handles this)
 * - Window lifecycle (ConveyorBeltSystemViewModel handles this)
 */
class FlexPaginator(private val repo: ChapterRepository) : WindowAssembler {

    override suspend fun assembleWindow(
        windowIndex: WindowIndex,
        firstChapter: ChapterIndex,
        lastChapter: ChapterIndex
    ): WindowData? = try {
        WindowData(
            html = buildHtml(windowIndex, firstChapter, lastChapter) ?: return null,
            firstChapter = firstChapter,
            lastChapter = lastChapter,
            windowIndex = windowIndex
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun buildHtml(win: WindowIndex, first: ChapterIndex, last: ChapterIndex): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">")
        sb.append("<style>")
        sb.append("*{box-sizing:border-box}html,body{margin:0;padding:0;width:100%;height:100%;overflow-x:hidden;overflow-y:auto}")
        sb.append("#flex-root{display:flex;flex-direction:column;width:100%;min-height:100%}")
        sb.append("section{flex-shrink:0;width:100%;padding:16px}")
        sb.append("body{font-family:-apple-system,sans-serif;font-size:16px;line-height:1.6;color:#000;background:#fff}")
        sb.append("p,div,span{word-wrap:break-word}img{max-width:100%;height:auto}")
        sb.append("</style></head><body>")
        sb.append("<div id=\"flex-root\" data-window-index=\"$win\">")
        
        for (i in first..last) {
            repo.getChapterHtml(i)?.let { content ->
                sb.append("<section data-chapter=\"$i\">$content</section>")
            }
        }
        
        sb.append("</div>")
        sb.append("<script src=\"file:///android_asset/flex_paginator.js\"></script>")
        sb.append("<script>if(window.flexPaginator){window.flexPaginator.configure({windowIndex:$win});window.flexPaginator.initialize();}</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    override fun canAssemble(windowIndex: WindowIndex, firstChapter: ChapterIndex, lastChapter: ChapterIndex): Boolean =
        windowIndex >= 0 && firstChapter >= 0 && lastChapter >= firstChapter &&
        (getTotalChapters().let { it == 0 || lastChapter < it })

    override fun getTotalChapters(): Int = repo.getTotalChapterCount()
}

/**
 * Interface for accessing chapter content.
 */
interface ChapterRepository {
    suspend fun getChapterHtml(chapterIndex: ChapterIndex): String?
    fun getTotalChapterCount(): Int
}
