package com.rokid.industry.hazardrecognition

import org.junit.Assert.*
import org.junit.Test

/** 隐患合并用例：构造假图片特征和隐患，演示内部去重缓存的输入及预期新增条数。 */
class HazardLedgerTest {
    private fun sample(y: Int = 100): FrameSample {
        val frame = Nv21Frame(ByteArray(12) { if (it < 8) y.toByte() else 128.toByte() }, 4, 2, 0, 1000)
        return FrameSample(frame, FrameSignature.from(frame))
    }
    private fun hazard(target: String = "左侧插座", existing: Int? = null) =
        Hazard("electrical", target, "插座明显烧蚀", "断电更换", 0, existing)

    // 用例：同位置、相似画面的同一隐患更新缓存，新增条数为 0。
    @Test fun repeatedHazardsUpdateExistingRecord() {
        val ledger = HazardLedger()
        assertEquals(1, ledger.merge(listOf(hazard()), listOf(sample())))
        assertEquals(0, ledger.merge(listOf(hazard()), listOf(sample(102))))
        assertEquals(1, ledger.all().size)
    }
    // 用例：模型给出的旧 ID 不是最终依据；位置或场景不同必须保留为另一条。
    @Test fun modelExistingIdCannotMergeDifferentObjectsOrScenes() {
        val ledger = HazardLedger()
        ledger.merge(listOf(hazard()), listOf(sample()))
        assertEquals(1, ledger.merge(listOf(hazard("右侧插座", 1)), listOf(sample())))
        assertEquals(1, ledger.merge(listOf(hazard(existing = 1)), listOf(sample(220))))
    }
    // 用例：新结果无隐患只替换界面，不清除内部用于去重的已知隐患。
    @Test fun clearFrameDoesNotEraseUnresolvedInspectionRecords() {
        val ledger = HazardLedger()
        ledger.merge(listOf(hazard()), listOf(sample()))
        assertEquals(0, ledger.merge(emptyList(), listOf(sample())))
        assertEquals(1, ledger.known().size)
    }
}
