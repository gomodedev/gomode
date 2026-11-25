// TxSprite: indexed-color sprite sent to the device — its palette and 1/2/4bpp pixel packing.
// Tx (host→device): typed messages sent via HaloDevice.sendMessage().
// Rx (device→host): parsed from HaloDevice.dataResponse Flow.
package com.fghbuild.halo.msg

data class TxSprite(
    val width: Int,
    val height: Int,
    val bpp: Int,
    val numColors: Int,
    val palette: ByteArray, // RGB triplets, numColors × 3
    val pixels: ByteArray, // packed pixel data
) : TxMessage {
    companion object {
        internal fun packBits(
            indices: IntArray,
            bpp: Int,
        ): ByteArray =
            when (bpp) {
                1 -> {
                    ByteArray((indices.size + 7) / 8).also { out ->
                        for (i in indices.indices) {
                            out[i / 8] = (out[i / 8].toInt() or ((indices[i] and 0x01) shl (7 - i % 8))).toByte()
                        }
                    }
                }

                2 -> {
                    ByteArray((indices.size + 3) / 4).also { out ->
                        for (i in indices.indices) {
                            val shift = (3 - i % 4) * 2
                            out[i / 4] = (out[i / 4].toInt() or ((indices[i] and 0x03) shl shift)).toByte()
                        }
                    }
                }

                4 -> {
                    ByteArray((indices.size + 1) / 2).also { out ->
                        for (i in indices.indices) {
                            val shift = (1 - i % 2) * 4
                            out[i / 2] = (out[i / 2].toInt() or ((indices[i] and 0x0F) shl shift)).toByte()
                        }
                    }
                }

                else -> {
                    throw IllegalArgumentException("Unsupported bpp: $bpp")
                }
            }
    }

    override fun pack(): ByteArray {
        val header = ByteArray(7)
        header[0] = (width shr 8).toByte()
        header[1] = (width and 0xFF).toByte()
        header[2] = (height shr 8).toByte()
        header[3] = (height and 0xFF).toByte()
        header[4] = 0 // compressed flag
        header[5] = bpp.toByte()
        header[6] = numColors.toByte()

        return header + palette + pixels
    }
}
