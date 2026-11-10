package com.delightroom.mission.app.api

import com.delightroom.mission.core.testfixture.ArchitectureRules
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** common · core · infra · api 의 계층 · 의존 규칙. */
class ArchitectureTest {
    private val classes = ArchitectureRules.importProductionClasses()

    @TestFactory
    fun rules(): List<DynamicTest> =
        ArchitectureRules.all.map { rule ->
            DynamicTest.dynamicTest(rule.description) { rule.check(classes) }
        }
}
