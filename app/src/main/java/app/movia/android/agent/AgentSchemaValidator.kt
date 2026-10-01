package app.movia.android.agent

import org.json.JSONArray
import org.json.JSONObject

/** Validate the advertised contract before defaults or coercion can hide a bad command. */
internal object AgentSchemaValidator {
    fun validate(value: Any?, schema: JSONObject, path: String = "arguments") {
        val type = schema.optString("type")
        val valid = when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
            "number" -> value is Number && value.toDouble().isFinite()
            else -> true
        }
        require(valid) { "$path: expected $type" }
        if (value is JSONObject) {
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val required = schema.optJSONArray("required") ?: JSONArray()
            for (i in 0 until required.length()) require(value.has(required.getString(i))) {
                "$path.${required.getString(i)}: required"
            }
            for (key in value.keys()) {
                val field = properties.optJSONObject(key)
                require(field != null || schema.optBoolean("additionalProperties", false)) { "$path.$key: unknown field" }
                if (field != null) validate(value.get(key), field, "$path.$key")
            }
        }
        if (value is JSONArray) {
            require(value.length() <= 64) { "$path: too many items" }
            schema.optJSONObject("items")?.let { item ->
                for (i in 0 until value.length()) validate(value.get(i), item, "$path[$i]")
            }
        }
        if (value is String) require(value.length <= 4096) { "$path: value too long" }
        if (value is Number) {
            if (schema.has("minimum")) require(value.toDouble() >= schema.getDouble("minimum")) { "$path: below minimum" }
            if (schema.has("maximum")) require(value.toDouble() <= schema.getDouble("maximum")) { "$path: above maximum" }
        }
        schema.optJSONArray("enum")?.let { allowed ->
            require((0 until allowed.length()).any { allowed.get(it) == value }) { "$path: unknown value" }
        }
    }
}
