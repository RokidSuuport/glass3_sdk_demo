package com.rokid.phone.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rokid.security.phone.sdk.base.utils.log.L

data class CustomMessage(
    var type: String = "", var message: String = ""
) {
    companion object {
        private const val CUSTOM_BUSINESS_ACTION = "custom_business_action"

        /**
         * 经典蓝牙文本消息的统一解析入口。
         *
         * SDK 回调里的 msg 不一定都是项目内部的 CustomMessage JSON：
         * 1. 系统业务消息：{"type":"POWER_UPDATE","message":"..."}
         * 2. SDK 包装消息：{"type":"custom_business_action","extra":"..."}
         * 3. 普通测试文本：眼镜端发送蓝牙测试消息
         *
         * 所以这里先判断格式，再决定是否转换成 CustomMessage，避免普通文本被 Gson
         * 当作对象解析时抛出 Expected BEGIN_OBJECT but was STRING。
         */
        fun fromClassicBtPayload(gson: Gson, msg: String): CustomMessage? {
            val root = try {
                JsonParser.parseString(msg)
            } catch (_: Exception) {
                // msg 是普通文本或非法 JSON 时，不属于系统业务消息，交给调用方直接忽略。
                return null
            }

            if (!root.isJsonObject) {
                // 即使是合法 JSON，也只有对象格式才可能承载 type/message 字段。
                L.d("--------->不是json字符串")
                return null
            }

            val jsonObject = root.asJsonObject
            val type = jsonObject.stringOrNull("type").orEmpty()
            val message = jsonObject.messageOrNull(gson, "message")

            if (type == CUSTOM_BUSINESS_ACTION) {
                // SDK 可能把真实内容放在 extra 中；extra 也可能再次包着项目业务 JSON。
                val extra = jsonObject.messageOrNull(gson, "extra").orEmpty()
                return fromClassicBtPayload(gson, extra) ?: CustomMessage(type, extra)
            }

            // 项目内部业务消息保持 type/message 结构，交给上层按 type 分发处理。
            return CustomMessage(type, message.orEmpty())
        }

        /**
         * 安全读取字符串字段。
         *
         * 字段不存在、为 null、或不是 primitive 时返回 null，避免直接 asString 崩溃。
         */
        private fun JsonObject.stringOrNull(key: String): String? {
            val element: JsonElement = get(key) ?: return null
            if (element.isJsonNull || !element.isJsonPrimitive) {
                return null
            }
            return element.asString
        }

        /**
         * 安全读取 message/extra 这类内容字段。
         *
         * 如果字段本身是字符串，直接返回字符串；如果字段是 JSON 对象或数组，
         * 转回 JSON 字符串，方便后续继续用 Gson 解析成 RKSystemInfo 等业务对象。
         */
        private fun JsonObject.messageOrNull(gson: Gson, key: String): String? {
            val element: JsonElement = get(key) ?: return null
            if (element.isJsonNull) {
                return null
            }
            return if (element.isJsonPrimitive) {
                element.asString
            } else {
                gson.toJson(element)
            }
        }
    }
}
