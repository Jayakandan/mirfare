package com.example.mifareamount

import android.nfc.Tag
import android.nfc.tech.MifareClassic

/**
 * Reads and writes the balance to the SAME place and format as the Teensy
 * PN532 sketch, so a card can be topped up on the phone and spent on the
 * Teensy (and vice versa).
 *
 * Location : sector 1, block 0 = absolute block 4.
 * Format   : bytes 0..3  = marker 'B','A','L','1'
 *            bytes 4..7  = amount, 32-bit BIG-endian
 *            bytes 8..15 = 0
 *
 * A block without the marker is treated as blank (no balance written yet).
 */
object MifareAmount {

    private const val SECTOR = 1
    private const val BLOCK  = 4                      // balance  (sector 1, block 0)
    private const val NAME_BLOCK = 5                  // name     (sector 1, block 1)
    const val NAME_MAX_LEN = 16                       // one MIFARE block
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())

    /** Balance is a signed 32-bit int; refuse anything that would wrap around. */
    const val MAX_AMOUNT = 2_000_000_000

    // Same candidate keys the Teensy tries, so both agree on how to unlock.
    private val DEFAULT_KEYS: List<ByteArray> = listOf(
        h(0xFF,0xFF,0xFF,0xFF,0xFF,0xFF),
        h(0x00,0x00,0x00,0x00,0x00,0x00),
        h(0xA0,0xA1,0xA2,0xA3,0xA4,0xA5),
        h(0xD3,0xF7,0xD3,0xF7,0xD3,0xF7),
        h(0xB0,0xB1,0xB2,0xB3,0xB4,0xB5),
        h(0x4D,0x3A,0x99,0xC3,0x51,0xDD),
        h(0x1A,0x98,0x2C,0x7E,0x45,0x9A),
        h(0xAA,0xBB,0xCC,0xDD,0xEE,0xFF),
        h(0x71,0x4C,0x5C,0x88,0x6E,0x97),
        h(0x58,0x7E,0xE5,0xF9,0x35,0x0F)
    )

    /**
     * @param amount   balance on the card after the operation
     * @param previous balance before the operation (top-up only)
     * @param keyUsed  which key unlocked sector 1, for troubleshooting
     */
    data class Result(
        val ok: Boolean,
        val message: String,
        val amount: Int? = null,
        val previous: Int? = null,
        val keyUsed: String? = null,
        val name: String? = null
    )

    fun uidHex(tag: Tag): String = tag.id.joinToString(":") { "%02X".format(it) }

    fun parseKey(hex: String?): ByteArray? {
        if (hex == null) return null
        val clean = hex.trim().replace(" ", "").replace(":", "")
        if (clean.length != 12) return null
        return try { ByteArray(6) { clean.substring(it*2, it*2+2).toInt(16).toByte() } }
        catch (e: Exception) { null }
    }

    private fun keysToTry(userHex: String?): List<ByteArray> {
        val user = parseKey(userHex)
        return if (user != null) listOf(user) + DEFAULT_KEYS else DEFAULT_KEYS
    }

    private fun keyHex(k: ByteArray): String = k.joinToString("") { "%02X".format(it) }

    fun readAmount(tag: Tag, userKeyHex: String?): Result {
        val mfc = MifareClassic.get(tag) ?: return Result(false, "Not a MIFARE Classic card")
        return try {
            mfc.connect()
            val key = authenticate(mfc, userKeyHex)
                ?: return Result(false, "Auth failed - card key not in the list")
            val who = parseName(runCatching { mfc.readBlock(NAME_BLOCK) }.getOrNull())
            val amt = parseBlock(mfc.readBlock(BLOCK))
                ?: return Result(false, "Blank card - no balance written yet", 0,
                                 keyUsed = keyHex(key), name = who)
            Result(true, "Read OK (block 4 balance, block 5 name)", amt,
                   keyUsed = keyHex(key), name = who)
        } catch (e: Exception) {
            Result(false, "Read error: ${e.message}")
        } finally { runCatching { mfc.close() } }
    }

    /** Overwrite the balance with an absolute value, and optionally set the name. */
    fun writeAmount(tag: Tag, amount: Int, userKeyHex: String?, name: String? = null): Result {
        if (amount < 0) return Result(false, "Amount cannot be negative")
        if (amount > MAX_AMOUNT) return Result(false, "Amount too large (max $MAX_AMOUNT)")
        val mfc = MifareClassic.get(tag) ?: return Result(false, "Not a MIFARE Classic card")
        return try {
            mfc.connect()
            val key = authenticate(mfc, userKeyHex)
                ?: return Result(false, "Auth failed - card key not in the list")
            val before = parseBlock(mfc.readBlock(BLOCK))
            val who = writeNameIfGiven(mfc, name)
            mfc.writeBlock(BLOCK, buildBlock(amount))
            val back = parseBlock(mfc.readBlock(BLOCK))
            if (back == amount)
                Result(true, "Wrote $amount to block 4 (verified)", back, before, keyHex(key), who)
            else
                Result(false, "Verify failed - card reads $back", back, before, keyHex(key), who)
        } catch (e: Exception) {
            Result(false, "Write error: ${e.message}")
        } finally { runCatching { mfc.close() } }
    }

    /**
     * Read current balance, add [delta] (negative to spend), write back to block 4.
     * This is the top-up path: the amount you type is ADDED to what is already
     * on the card, then verified by reading the block back.
     */
    fun topUp(tag: Tag, delta: Int, userKeyHex: String?, name: String? = null): Result {
        if (delta == 0) return Result(false, "Enter a non-zero amount")
        val mfc = MifareClassic.get(tag) ?: return Result(false, "Not a MIFARE Classic card")
        return try {
            mfc.connect()
            val key = authenticate(mfc, userKeyHex)
                ?: return Result(false, "Auth failed - card key not in the list")
            val who = writeNameIfGiven(mfc, name)
            val current = parseBlock(mfc.readBlock(BLOCK)) ?: 0     // blank card starts at 0
            val updated = current.toLong() + delta.toLong()          // widen so we can range-check
            if (updated < 0)
                return Result(false, "Insufficient balance - card has $current",
                              current, current, keyHex(key), who)
            if (updated > MAX_AMOUNT)
                return Result(false, "Balance would exceed $MAX_AMOUNT",
                              current, current, keyHex(key), who)
            val newAmount = updated.toInt()
            mfc.writeBlock(BLOCK, buildBlock(newAmount))
            val back = parseBlock(mfc.readBlock(BLOCK))
            if (back == newAmount) {
                val sign = if (delta > 0) "+$delta" else "$delta"
                Result(true, "Topped up $sign  ($current -> $newAmount)",
                       back, current, keyHex(key), who)
            } else {
                Result(false, "Verify failed - card reads $back", back, current, keyHex(key), who)
            }
        } catch (e: Exception) {
            Result(false, "Top-up error: ${e.message}")
        } finally { runCatching { mfc.close() } }
    }

    private fun authenticate(mfc: MifareClassic, userKeyHex: String?): ByteArray? {
        for (k in keysToTry(userKeyHex)) {
            if (runCatching { mfc.authenticateSectorWithKeyA(SECTOR, k) }.getOrDefault(false)) return k
            if (runCatching { mfc.authenticateSectorWithKeyB(SECTOR, k) }.getOrDefault(false)) return k
        }
        return null
    }

    /**
     * Writes [name] to block 5 if one was supplied, matching the Teensy layout
     * (ASCII, truncated to 16 bytes, null-padded). Returns whatever name the
     * card holds afterwards, or null if the block is blank.
     */
    private fun writeNameIfGiven(mfc: MifareClassic, name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            val b = ByteArray(16)
            val src = trimmed.take(NAME_MAX_LEN).toByteArray(Charsets.US_ASCII)
            System.arraycopy(src, 0, b, 0, src.size)
            runCatching { mfc.writeBlock(NAME_BLOCK, b) }
        }
        return parseName(runCatching { mfc.readBlock(NAME_BLOCK) }.getOrNull())
    }

    // Block 5 holds the cardholder name: ASCII, null-terminated. data[0]==0 means blank.
    private fun parseName(b: ByteArray?): String? {
        if (b == null || b.isEmpty() || b[0] == 0.toByte()) return null
        val end = b.indexOfFirst { it == 0.toByte() }.let { if (it < 0) b.size else it }
        return String(b, 0, end, Charsets.US_ASCII).trim().ifEmpty { null }
    }

    // 16-byte block: MAGIC + big-endian amount, matching the Teensy exactly.
    private fun buildBlock(amount: Int): ByteArray {
        val b = ByteArray(16)
        System.arraycopy(MAGIC, 0, b, 0, 4)
        b[4] = (amount ushr 24).toByte()
        b[5] = (amount ushr 16).toByte()
        b[6] = (amount ushr 8).toByte()
        b[7] = amount.toByte()
        return b
    }

    // Returns the amount only if the marker matches; else null (blank card).
    private fun parseBlock(b: ByteArray): Int? {
        if (b.size < 8) return null
        for (i in 0..3) if (b[i] != MAGIC[i]) return null
        return ((b[4].toInt() and 0xFF) shl 24) or
               ((b[5].toInt() and 0xFF) shl 16) or
               ((b[6].toInt() and 0xFF) shl 8) or
               (b[7].toInt() and 0xFF)
    }

    private fun h(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}
