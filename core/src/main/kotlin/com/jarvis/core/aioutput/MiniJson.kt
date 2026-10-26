package com.jarvis.core.aioutput

/**
 * Minimal JSON value model for Structured AI Output (no Android org.json stubs).
 */
sealed class JsonVal {
    data class Obj(val map: LinkedHashMap<String, JsonVal>) : JsonVal()
    data class Arr(val items: List<JsonVal>) : JsonVal()
    data class Str(val value: String) : JsonVal()
    data class Num(val value: Double) : JsonVal()
    data class Bool(val value: Boolean) : JsonVal()
    data object Null : JsonVal()

    fun asObj(): Obj? = this as? Obj
    fun asArr(): Arr? = this as? Arr
    fun asString(): String? = when (this) {
        is Str -> value
        is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        else -> null
    }

    fun opt(key: String): JsonVal? = asObj()?.map?.get(key)
    fun has(key: String): Boolean = asObj()?.map?.containsKey(key) == true
    fun keys(): Set<String> = asObj()?.map?.keys.orEmpty()

    fun nestingDepth(depth: Int = 0): Int = when (this) {
        is Obj -> (map.values.maxOfOrNull { it.nestingDepth(depth + 1) } ?: depth)
        is Arr -> (items.maxOfOrNull { it.nestingDepth(depth + 1) } ?: depth)
        else -> depth
    }
}

/**
 * Tiny JSON parser sufficient for schema v1 envelopes (objects/arrays/scalars).
 */
object MiniJson {
    fun parse(text: String): JsonVal {
        val p = Parser(text.trim())
        val v = p.parseValue()
        p.skipWs()
        if (!p.eof()) error("trailing")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun eof() = i >= s.length
        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): JsonVal {
            skipWs()
            if (eof()) error("eof")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonVal.Str(parseString())
                't' -> { expect("true"); JsonVal.Bool(true) }
                'f' -> { expect("false"); JsonVal.Bool(false) }
                'n' -> { expect("null"); JsonVal.Null }
                else -> if (c == '-' || c.isDigit()) {
                    JsonVal.Num(parseNumber())
                } else {
                    error("unexpected")
                }
            }
        }

        private fun parseObject(): JsonVal.Obj {
            expect('{')
            val map = linkedMapOf<String, JsonVal>()
            skipWs()
            if (peek('}')) {
                i++
                return JsonVal.Obj(map)
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when {
                    peek(',') -> {
                        i++
                        continue
                    }
                    peek('}') -> {
                        i++
                        break
                    }
                    else -> error("object")
                }
            }
            return JsonVal.Obj(map)
        }

        private fun parseArray(): JsonVal.Arr {
            expect('[')
            val items = mutableListOf<JsonVal>()
            skipWs()
            if (peek(']')) {
                i++
                return JsonVal.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWs()
                when {
                    peek(',') -> {
                        i++
                        continue
                    }
                    peek(']') -> {
                        i++
                        break
                    }
                    else -> error("array")
                }
            }
            return JsonVal.Arr(items)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (!eof()) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) error("escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) error("unicode")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> error("bad_escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("unclosed_string")
        }

        private fun parseNumber(): Double {
            val start = i
            if (peek('-')) i++
            while (i < s.length && s[i].isDigit()) i++
            if (peek('.')) {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (peek('e') || peek('E')) {
                i++
                if (peek('+') || peek('-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDouble()
        }

        private fun expect(ch: Char) {
            skipWs()
            if (eof() || s[i] != ch) error("expected_$ch")
            i++
        }

        private fun expect(lit: String) {
            skipWs()
            if (!s.startsWith(lit, i)) error("expected_$lit")
            i += lit.length
        }

        private fun peek(ch: Char): Boolean = i < s.length && s[i] == ch
    }
}
