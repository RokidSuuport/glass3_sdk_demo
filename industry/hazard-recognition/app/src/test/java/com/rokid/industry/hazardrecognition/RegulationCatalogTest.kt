package com.rokid.industry.hazardrecognition

import org.junit.Assert.*
import org.junit.Test

/** 用例：验证已支持类别都有可追溯的法规参考，同时保留通用职责与处置要求的区别。 */
class RegulationCatalogTest {
    @Test fun commonHazardsHaveReferencesAndBriefRequirements() {
        val cases = mapOf(
            "evacuation_blockage" to "第28条",
            "hydrant_obstruction" to "第28条",
            "extinguisher_issue" to "第16条第1款第2项",
            "electrical" to "第27条第2款",
            "charging" to "第27条第2款",
        )
        cases.forEach { (category, article) ->
            val reference = RegulationCatalog.reference(category)
            assertTrue(category, reference.contains(article))
            assertTrue(category, reference.contains("（参考）"))
            assertTrue(category, reference.substringAfter("\n", "").isNotBlank())
        }
    }

    @Test fun broadCategoriesDoNotPretendToBeSpecificViolationFindings() {
        val combustible = RegulationCatalog.reference("combustible")
        assertTrue(combustible.contains("第16条第1款第5项"))
        assertTrue(combustible.contains("通用职责"))
        assertFalse(combustible.contains("第23条"))
        val fire = RegulationCatalog.reference("fire")
        assertTrue(fire.contains("第44条第1款"))
        assertTrue(fire.contains("处置要求"))
        assertFalse(fire.contains("第21条"))
        listOf("other", "", "invented_category").forEach {
            assertTrue(RegulationCatalog.reference(it).startsWith("待核实"))
        }
    }
}
