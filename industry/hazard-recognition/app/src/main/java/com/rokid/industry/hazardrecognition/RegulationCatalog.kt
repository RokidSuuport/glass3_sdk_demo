package com.rokid.industry.hazardrecognition

/**
 * 用例：将模型返回的 category 映射为“法条 + 简短要求”，直接供眼镜纵向表格展示。
 * 提示词要求模型描述图片；表格的法条取自这里。正式使用可接入开发者自己的法规数据库。
 * 来源：《中华人民共和国消防法》（2021 修正），2026-09-09 核对：
 * https://www.beijing.gov.cn/zhengce/zhengcefagui/qtwj/202307/t20230726_3207767.html
 * 第 16 条针对单位职责；第 27 条还需结合具体技术标准核查，不凭图片作违法认定。
 */
object RegulationCatalog {
    fun reference(category: String): String = when (category) {
        "evacuation_blockage" -> "消防法第28条（参考）\n不得堵塞疏散通道、安全出口"
        "hydrant_obstruction" -> "消防法第28条（参考）\n不得埋压、圈占、遮挡消火栓"
        "extinguisher_issue" -> "消防法第16条第1款第2项（参考）\n单位应维护消防器材，确保完好有效"
        "electrical", "charging" -> "消防法第27条第2款（参考）\n用电及线路须符合消防技术标准"
        // 普通可燃物不等于易燃易爆危险品；不直接套用危险品或仓库专门条款。
        "combustible" -> "消防法第16条第1款第5项（通用职责）\n单位应检查并及时消除火灾隐患"
        // 烟火类别不必然代表违规用火；提供发现火灾后的法定处置要求，并明确其性质。
        "fire" -> "消防法第44条第1款（处置要求）\n发现火灾应立即报警"
        else -> "待核实\n暂未匹配具体条款"
    }
}
