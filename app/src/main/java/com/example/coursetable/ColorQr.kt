package com.example.coursetable

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 多色二维码（HCC2D 思路的简化实现）：
 * 把数据切成 3 段，分别编码成 3 张同版本的标准二维码，
 * 再叠加到 R / G / B 三个颜色通道上 —— 每个模块呈现 8 种颜色之一，容量为普通二维码的 3 倍。
 * 解码时拆分通道，各通道按普通二维码识别后拼接。
 */
object ColorQr {
    private const val LAYERS = 3
    private const val MAGIC = 0x43 // 'C'
    private const val MAGIC2 = 0x51 // 'Q'
    private const val HEADER = 4    // magic, magic2, index, count

    // 不同版本在 EC=L 下 8-bit 字节模式的容量
    private val CAPACITY_L = intArrayOf(
        0, 17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425, 458, 520, 586, 644, 718, 792, 858,
        929, 1003, 1091, 1171, 1273, 1367, 1465, 1528, 1628, 1732, 1840, 1952, 2068, 2188, 2303, 2431, 2563, 2699, 2809, 2953
    )

    class TooLargeException : Exception("数据过多，无法放入单个二维码")

    fun compress(text: String): ByteArray = ByteArrayOutputStream().use { bos ->
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        bos.toByteArray()
    }

    fun decompress(bytes: ByteArray): String =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    /** 生成多色二维码位图 */
    fun encode(payload: ByteArray, sizePx: Int): Bitmap {
        val chunk = (payload.size + LAYERS - 1) / LAYERS
        val perLayer = chunk + HEADER
        val version = CAPACITY_L.indexOfFirst { it >= perLayer }.takeIf { it > 0 } ?: throw TooLargeException()

        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.QR_VERSION to version,
            EncodeHintType.MARGIN to 0
        )
        val matrices = ArrayList<BitMatrix>(LAYERS)
        for (i in 0 until LAYERS) {
            val from = i * chunk
            val to = minOf(payload.size, from + chunk)
            val part = ByteArray(HEADER + maxOf(0, to - from))
            part[0] = MAGIC.toByte(); part[1] = MAGIC2.toByte(); part[2] = i.toByte(); part[3] = LAYERS.toByte()
            if (to > from) System.arraycopy(payload, from, part, HEADER, to - from)
            val text = String(part, Charsets.ISO_8859_1)
            matrices.add(writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints))
        }
        val modules = matrices[0].width
        require(matrices.all { it.width == modules })

        val quiet = 4
        val total = modules + quiet * 2
        val scale = maxOf(1, sizePx / total)
        val px = total * scale
        val pixels = IntArray(px * px) { Color.WHITE }
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                val r = if (matrices[0].get(x, y)) 0 else 255
                val g = if (matrices[1].get(x, y)) 0 else 255
                val b = if (matrices[2].get(x, y)) 0 else 255
                val color = Color.rgb(r, g, b)
                val ox = (x + quiet) * scale
                val oy = (y + quiet) * scale
                for (dy in 0 until scale) {
                    val row = (oy + dy) * px
                    for (dx in 0 until scale) pixels[row + ox + dx] = color
                }
            }
        }
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
    }

    /** 从 ARGB 像素解码；返回 null 表示未识别到完整的多色二维码 */
    fun decode(pixels: IntArray, width: Int, height: Int): ByteArray? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "ISO-8859-1"
                )
            )
        }
        val parts = arrayOfNulls<ByteArray>(LAYERS)
        val channel = IntArray(pixels.size)
        for (layer in 0 until LAYERS) {
            val shift = when (layer) { 0 -> 16; 1 -> 8; else -> 0 }
            for (i in pixels.indices) {
                val v = (pixels[i] shr shift) and 0xFF
                channel[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val source = RGBLuminanceSource(width, height, channel)
            val result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (_: Exception) {
                try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))) } catch (_: Exception) { null }
            } finally { reader.reset() }
            val bytes = result?.text?.toByteArray(Charsets.ISO_8859_1) ?: return null
            if (bytes.size < HEADER || bytes[0].toInt() != MAGIC || bytes[1].toInt() != MAGIC2) return null
            val idx = bytes[2].toInt()
            if (idx !in 0 until LAYERS) return null
            parts[idx] = bytes.copyOfRange(HEADER, bytes.size)
        }
        if (parts.any { it == null }) return null
        return ByteArrayOutputStream().use { bos -> parts.forEach { bos.write(it!!) }; bos.toByteArray() }
    }

    fun decode(bitmap: Bitmap): ByteArray? {
        // 大图先缩放，加快识别
        val maxSide = 1200
        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxSide) {
            val s = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
        } else bitmap
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return decode(pixels, scaled.width, scaled.height)
    }
}

/**
 * 面对面共享的紧凑二进制编码（比 gzip(JSON) 小 3～6 倍）：
 * 去掉 UUID 等冗余字段，字符串建表去重，数值按字节打包，再用 Deflate 最高压缩等级压缩。
 * 解码后还原为 importJson 可识别的 JSON。
 */
object ShareCodec {
    private const val MAGIC = "CT2"
    private val EPOCH: java.time.LocalDate = java.time.LocalDate.of(2000, 1, 1)

    private fun java.io.DataOutputStream.writeVar(v: Int) {
        var x = v
        while (x >= 0x80) { writeByte((x and 0x7F) or 0x80); x = x ushr 7 }
        writeByte(x)
    }
    private fun java.io.DataInputStream.readVar(): Int {
        var shift = 0; var result = 0
        while (true) { val b = readUnsignedByte(); result = result or ((b and 0x7F) shl shift); if (b and 0x80 == 0) return result; shift += 7 }
    }
    private fun java.io.DataOutputStream.writeStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeVar(b.size); write(b) }
    private fun java.io.DataInputStream.readStr(): String { val n = readVar(); val b = ByteArray(n); readFully(b); return String(b, Charsets.UTF_8) }
    private fun minutes(t: String): Int = runCatching { t.split(":").let { it[0].toInt() * 60 + it[1].toInt() } }.getOrDefault(0)
    private fun hhmm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

    fun pack(repo: CourseRepository): ByteArray {
        val s = repo.settings
        val raw = ByteArrayOutputStream()
        java.io.DataOutputStream(raw).use { out ->
            out.writeBytes(MAGIC)
            out.writeVar(java.time.temporal.ChronoUnit.DAYS.between(EPOCH, s.semesterStart).toInt().coerceAtLeast(0))
            out.writeByte(s.totalWeeks); out.writeByte(s.periodsPerDay); out.writeByte(if (s.showWeekend) 1 else 0)
            // 作息时间：仅在与默认不同的情况下写入
            val custom = PeriodTimes.times.toList() != PeriodTimes.defaults
            out.writeByte(if (custom) 1 else 0)
            if (custom) {
                out.writeByte(PeriodTimes.times.size)
                PeriodTimes.times.forEach { (a, b) -> out.writeShort(minutes(a)); out.writeShort(minutes(b)) }
            }
            // 字符串表
            val courses = repo.courses.toList()
            val table = LinkedHashMap<String, Int>()
            fun idx(str: String): Int = table.getOrPut(str) { table.size }
            courses.forEach { idx(it.name); idx(it.teacher); idx(it.location) }
            out.writeVar(table.size)
            table.keys.forEach { out.writeStr(it) }
            out.writeVar(courses.size)
            courses.forEach { c ->
                out.writeVar(idx(c.name)); out.writeVar(idx(c.teacher)); out.writeVar(idx(c.location))
                // day(3bit) weekType(2bit) color(4bit) 合并到一个字节 + 节次/周次各半字节
                out.writeByte(((c.day and 7) shl 5) or ((c.weekType and 3) shl 3))
                out.writeByte(c.colorIndex and 0xFF)
                out.writeByte(((c.startPeriod and 0xF) shl 4) or (c.endPeriod and 0xF))
                out.writeByte(c.startWeek and 0xFF); out.writeByte(c.endWeek and 0xFF)
            }
        }
        val bytes = raw.toByteArray()
        val def = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        def.setInput(bytes); def.finish()
        val buf = ByteArray(bytes.size + 64)
        val n = def.deflate(buf); def.end()
        return byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte()) + buf.copyOf(n)
    }

    fun isV2(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 'C'.code.toByte() && bytes[1] == 'T'.code.toByte() && bytes[2] == '2'.code.toByte()

    /** 解码为 importJson 可用的 JSON 字符串 */
    fun unpackToJson(bytes: ByteArray): String {
        require(isV2(bytes)) { "不是 CT2 数据" }
        val inf = java.util.zip.Inflater(true)
        inf.setInput(bytes, 3, bytes.size - 3)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        inf.end()
        val inp = java.io.DataInputStream(out.toByteArray().inputStream())
        val magic = ByteArray(3); inp.readFully(magic)
        require(String(magic) == MAGIC) { "数据头错误" }
        val semesterStart = EPOCH.plusDays(inp.readVar().toLong())
        val totalWeeks = inp.readUnsignedByte(); val periodsPerDay = inp.readUnsignedByte(); val showWeekend = inp.readUnsignedByte() == 1
        var periodTimes: String? = null
        if (inp.readUnsignedByte() == 1) {
            val n = inp.readUnsignedByte()
            periodTimes = (0 until n).joinToString(",") { hhmm(inp.readUnsignedShort()) + "-" + hhmm(inp.readUnsignedShort()) }
        }
        val table = (0 until inp.readVar()).map { inp.readStr() }
        val count = inp.readVar()
        val arr = org.json.JSONArray()
        repeat(count) {
            val name = table[inp.readVar()]; val teacher = table[inp.readVar()]; val location = table[inp.readVar()]
            val b1 = inp.readUnsignedByte(); val color = inp.readUnsignedByte(); val b2 = inp.readUnsignedByte()
            val sw = inp.readUnsignedByte(); val ew = inp.readUnsignedByte()
            arr.put(Course(
                name = name, teacher = teacher, location = location,
                day = (b1 shr 5) and 7, weekType = (b1 shr 3) and 3, colorIndex = color,
                startPeriod = (b2 shr 4) and 0xF, endPeriod = b2 and 0xF, startWeek = sw, endWeek = ew
            ).toJson())
        }
        return org.json.JSONObject().apply {
            put("app", "CourseTable"); put("version", 1)
            put("settings", org.json.JSONObject().apply {
                put("semesterStart", semesterStart.toString()); put("totalWeeks", totalWeeks); put("periodsPerDay", periodsPerDay); put("showWeekend", showWeekend)
            })
            if (periodTimes != null) put("periodTimes", periodTimes)
            put("courses", arr)
        }.toString()
    }
}
