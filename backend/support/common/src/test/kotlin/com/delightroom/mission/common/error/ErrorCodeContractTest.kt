package com.delightroom.mission.common.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** ORACLE platform O12 — [ErrorCode] 가 docs/api-guide.md §4 표와 이름 · HTTP 상태 · 순서까지 같다. */
class ErrorCodeContractTest {
    private val row = Regex("""^\|\s*`([A-Z_]+)`\s*\|\s*(\d{3})\s*\|""")

    private fun guideTable(): List<Pair<String, Int>> {
        val path = requireNotNull(System.getProperty("apiGuide.path")) { "apiGuide.path 시스템 속성이 없다" }
        val section = File(path).readLines()
            .dropWhile { !it.startsWith("## 4.") }
            .drop(1)
            .takeWhile { !it.startsWith("## ") }
        return section.mapNotNull { line -> row.find(line)?.destructured?.let { (code, status) -> code to status.toInt() } }
    }

    @Test
    fun `O12 오류 코드가 api-guide 4 표와 같다`() {
        val table = guideTable()

        assertThat(table).isNotEmpty
        assertThat(ErrorCode.entries.map { it.name to it.httpStatus }).containsExactlyElementsOf(table)
    }
}
