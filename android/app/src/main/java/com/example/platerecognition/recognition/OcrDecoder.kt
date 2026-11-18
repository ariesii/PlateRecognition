package com.example.platerecognition.recognition

/** Upstream PlateRecognitionOCRV6 medium's native output-class indices (not a compact alphabet). */
object OcrDecoder {
    private val characters = listOf(
        "京", "沪", "津", "渝", "冀", "晋", "蒙", "辽", "吉", "黑", "苏",
        "浙", "皖", "闽", "赣", "鲁", "豫", "鄂", "湘", "粤", "桂", "琼",
        "川", "贵", "云", "藏", "陕", "甘", "青", "宁", "新", "学", "警",
        "港", "澳", "挂", "使", "领", "民", "航", "危", "0", "1", "2",
        "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D",
        "E", "F", "G", "H", "J", "K", "L", "M", "N", "P", "Q",
        "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "险", "品"
    )
    private val fullIndices = intArrayOf(
        2208, 7820, 7911, 8106, 2753, 6668, 12308, 14562, 3223, 17483,
        11961, 7953, 9652, 15746, 14095, 16993, 13892, 14823, 8151, 10722,
        7043, 9208, 5031, 14049, 2187, 12566, 15822, 9360, 16003, 4582,
        6550, 4564, 13672, 8121, 8413, 6047, 2370, 16238, 7702, 11833,
        3113, 33, 34, 35, 36, 37, 38, 39, 40, 41,
        42, 43, 44, 45, 46, 47, 48, 49, 50, 52,
        53, 54, 55, 56, 58, 59, 60, 61, 62, 63,
        64, 65, 66, 67, 68, 15837, 3376
    )
    private val characterByClass = fullIndices.indices.associate { fullIndices[it] to characters[it] }
    init { require(characters.size == 77 && fullIndices.size == 77 && characterByClass.size == 77) }

    fun decode(logits: FloatArray, shape: LongArray): String {
        require(shape.size == 3 && shape[0] == 1L && shape[1] > 0 &&
                shape[2] == 18_710L && shape[1] * shape[2] == logits.size.toLong()) {
            "Unexpected PP-OCRv6 medium output: ${shape.contentToString()}"
        }
        val classes = shape[2].toInt()
        val text = StringBuilder()
        var previous = classes
        for (step in 0 until shape[1].toInt()) {
            val offset = step * classes
            var best = 0
            for (c in 1 until classes) {
                if (logits[offset + c] > logits[offset + best]) best = c
            }
            if (best != 0 && best != previous) characterByClass[best]?.let(text::append)
            // Unmapped classes still separate equal mapped classes, just like upstream.
            previous = best
        }
        return text.toString()
    }

    fun modelClassFor(character: String): Int = fullIndices[characters.indexOf(character).also {
        require(it >= 0) { "Unknown character $character" }
    }]
}
