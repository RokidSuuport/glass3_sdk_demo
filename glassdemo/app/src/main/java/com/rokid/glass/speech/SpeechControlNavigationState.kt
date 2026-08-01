package com.rokid.glass.speech

data class SpeechControlPosition(
    val groupIndex: Int,
    val itemIndex: Int,
    val globalIndex: Int,
)

class SpeechControlNavigationState(private val groupSizes: List<Int>) {
    private val totalSize: Int
    private var globalIndex = 0

    init {
        require(groupSizes.isNotEmpty()) { "At least one control group is required" }
        require(groupSizes.all { it > 0 }) { "Every control group must contain an item" }
        totalSize = groupSizes.sum()
    }

    val current: SpeechControlPosition
        get() = positionFor(globalIndex)

    fun select(groupIndex: Int, itemIndex: Int): SpeechControlPosition {
        require(groupIndex in groupSizes.indices) { "Unknown control group: $groupIndex" }
        require(itemIndex in 0 until groupSizes[groupIndex]) {
            "Unknown item $itemIndex in group $groupIndex"
        }
        globalIndex = groupSizes.take(groupIndex).sum() + itemIndex
        return current
    }

    fun moveForward(): SpeechControlPosition = moveBy(1)

    fun moveBackward(): SpeechControlPosition = moveBy(-1)

    private fun moveBy(offset: Int): SpeechControlPosition {
        globalIndex = Math.floorMod(globalIndex + offset, totalSize)
        return current
    }

    private fun positionFor(index: Int): SpeechControlPosition {
        var remaining = index
        groupSizes.forEachIndexed { groupIndex, groupSize ->
            if (remaining < groupSize) {
                return SpeechControlPosition(groupIndex, remaining, index)
            }
            remaining -= groupSize
        }
        error("Control position is outside configured groups")
    }
}
