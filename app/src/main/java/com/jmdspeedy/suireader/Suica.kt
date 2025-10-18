package com.jmdspeedy.suireader

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*

data class HistoryBlock(
    val consoleType: String,
    val processType: String,
    val date: String,
    val balance: Int,
    val entryStationCode: String?,
    val exitStationCode: String?,
    val entryStationName: String?,
    val exitStationName: String?,
    val entryAreaCode: Int?,
    val exitAreaCode: Int?
)

data class SuicaData(
    val cardId: String,
    val manufacturer: String,
    val systemCode: String,
    val balance: Int?,
    val transactionHistory: List<HistoryBlock>
)

object Suica {
    private var stationMap: Map<String, String>? = null

    /**
     * Initializes the station map by loading data from StationCode.csv in the assets folder.
     * This should be called once, preferably in your Application class or main activity's onCreate.
     */
    fun init(context: Context) {
        if (stationMap == null) {
            try {
                context.assets.open("StationCode.csv").use { inputStream ->
                    stationMap = loadStationMapFromCsv(inputStream)
                    Log.d("Suica", "Station map loaded with ${stationMap?.size} entries.")
                }
            } catch (e: IOException) {
                Log.e("Suica", "Error loading StationCode.csv. Make sure it's in the app/src/main/assets folder.", e)
            }
        }
    }

    fun extractData(tag: Tag): SuicaData? {
        val nfcF = NfcF.get(tag)
        return try {
            nfcF.connect()
            val history = readSuicaHistory(nfcF)
            SuicaData(
                cardId = toReversedHex(tag.id),
                manufacturer = toHex(nfcF.manufacturer),
                systemCode = toHex(nfcF.systemCode),
                balance = history.firstOrNull()?.balance,
                transactionHistory = history
            )
        } catch (e: IOException) {
            Log.e("Suica", "FeliCa extraction error: ${e.message}")
            null
        } finally {
            try {
                if (nfcF.isConnected) {
                    nfcF.close()
                }
            } catch (e: IOException) {
                Log.e("Suica", "Error closing FeliCa connection: ${e.message}")
            }
        }
    }

    private fun readSuicaHistory(nfcF: NfcF): List<HistoryBlock> {
        val history = mutableListOf<HistoryBlock>()
        try {
            val idm = nfcF.tag.id
            val serviceCode = 0x090f
            val numBlocks = 20

            for (i in 0 until numBlocks) {
                val cmdPacket = mutableListOf<Byte>()
                cmdPacket.add(0x06) // Command: Read Without Encryption
                cmdPacket.addAll(idm.toList())
                cmdPacket.add(1) // Number of Services
                cmdPacket.add((serviceCode and 0xFF).toByte())
                cmdPacket.add((serviceCode shr 8 and 0xFF).toByte())
                cmdPacket.add(1) // Number of Blocks to read
                cmdPacket.add(0x80.toByte()) // 2-byte block list element
                cmdPacket.add(i.toByte())    // block number

                val readCmd = ByteArray(cmdPacket.size + 1)
                readCmd[0] = (cmdPacket.size + 1).toByte()
                System.arraycopy(cmdPacket.toByteArray(), 0, readCmd, 1, cmdPacket.size)

                val response = nfcF.transceive(readCmd)

                if (response.size > 12 && response[10] == 0x00.toByte()) {
                    val blockData = response.copyOfRange(13, 13 + 16)
                    if (blockData.any { it != 0.toByte() }) {
                        history.add(decodeSuicaHistoryBlock(blockData))
                    }
                } else {
                    break
                }
            }
        } catch (e: IOException) {
            Log.e("Suica", "Error reading Suica history: ${e.message}")
        }
        return history
    }

    private fun decodeSuicaHistoryBlock(blockData: ByteArray): HistoryBlock {
        val consoleType = when (val type = blockData[0].toInt() and 0xFF) {
            0x03 -> "Fare adjustment machine"
            0x04 -> "Portable terminal"
            0x05 -> "On-board terminal"
            0x07 -> "Ticket vending machine"
            0x08 -> "Ticket vending machine"
            0x09 -> "Deposit machine"
            0x12 -> "Ticket vending machine"
            0x16 -> "Ticket gate"
            0x17 -> "Simple ticket gate"
            0x18 -> "Window terminal"
            0x19 -> "Window terminal (TTC)"
            0x1A -> "Gate terminal"
            0x1B -> "Mobile phone"
            0x1C -> "Transfer adjustment machine"
            0x1D -> "Connecting gate"
            0x1F -> "Simple deposit machine"
            0x46 -> "VIEW ALTTE"
            0x48 -> "VIEW ALTTE"
            0xC7 -> "Vending machine/POS"
            0xC8 -> "Vending machine"
            else -> "Unknown ($type)"
        }

        val processType = when (val type = blockData[1].toInt() and 0xFF) {
            0x01 -> "Exit gate"
            0x02 -> "Charge"
            0x03 -> "Magnetic ticket purchase"
            0x04 -> "Fare adjustment"
            0x05 -> "Entry/Exit"
            0x06 -> "Exit at window"
            0x07 -> "New card"
            0x08 -> "Deduction at window"
            0x0D -> "Bus (PiTaPa?)"
            0x0F -> "Bus (IruCa?)"
            0x11 -> "Reissue"
            0x13 -> "Window operation"
            0x14 -> "Auto-charge"
            0x35 -> "Purchase"
            0x46 -> "Deposit (VIEW)"
            else -> "Unknown ($type)"
        }

        val dateInt = (blockData[4].toInt() and 0xFF shl 8) or (blockData[5].toInt() and 0xFF)
        val year = (dateInt shr 9) + 2000
        val month = (dateInt shr 5) and 0x0F
        val day = dateInt and 0x1F
        val date = "$year-$month-$day"

        val balance = (blockData[11].toInt() and 0xFF shl 8) or (blockData[10].toInt() and 0xFF)

        var entryStationCode: String? = null
        var exitStationCode: String? = null
        var entryAreaCode: Int? = null
        var exitAreaCode: Int? = null
        if (blockData[1].toInt() and 0xFF in listOf(0x01, 0x05, 0x06, 0x0D, 0x0F)) {
            val entryLineCode = blockData[6].toInt() and 0xFF
            val entryStationNum = blockData[7].toInt() and 0xFF
            val exitLineCode = blockData[8].toInt() and 0xFF
            val exitStationNum = blockData[9].toInt() and 0xFF
            val regionByte = blockData[15].toInt() and 0xFF
            entryAreaCode = (regionByte shr 6) and 0x03
            exitAreaCode = (regionByte shr 4) and 0x03

            entryStationCode = String.format("%02d%02x%02x", entryAreaCode, entryLineCode, entryStationNum)
            exitStationCode = String.format("%02d%02x%02x", exitAreaCode, exitLineCode, exitStationNum)
        }

        val entryStationName = entryStationCode?.let { lookupStationNameByHexCode(it) }
        val exitStationName = exitStationCode?.let { lookupStationNameByHexCode(it) }

        val historyBlock = HistoryBlock(
            consoleType = consoleType,
            processType = processType,
            date = date,
            balance = balance,
            entryStationCode = entryStationCode,
            exitStationCode = exitStationCode,
            entryStationName = entryStationName,
            exitStationName = exitStationName,
            entryAreaCode = entryAreaCode,
            exitAreaCode = exitAreaCode
        )

        logHistoryBlockDetails(historyBlock)

        return historyBlock
    }

    private fun logHistoryBlockDetails(block: HistoryBlock) {
        Log.d("SuicaDecoder", "--- Decoding History Block ---")
        Log.d("SuicaDecoder", "Console Type: ${block.consoleType}")
        Log.d("SuicaDecoder", "Process Type: ${block.processType}")
        Log.d("SuicaDecoder", "Date: ${block.date}")
        Log.d("SuicaDecoder", "Balance: ¥${block.balance}")
        Log.d("SuicaDecoder", "Entry Area: ${block.entryAreaCode}")
        Log.d("SuicaDecoder", "Entry Station Code: ${block.entryStationCode}")
        Log.d("SuicaDecoder", "Entry Station Name: ${block.entryStationName ?: "Not found"}")
        Log.d("SuicaDecoder", "Exit Area: ${block.exitAreaCode}")
        Log.d("SuicaDecoder", "Exit Station Code: ${block.exitStationCode}")
        Log.d("SuicaDecoder", "Exit Station Name: ${block.exitStationName ?: "Not found"}")
        Log.d("SuicaDecoder", "-----------------------------")
    }

    private fun loadStationMapFromCsv(input: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { br ->
            br.lineSequence()
                .drop(1) // Skip header row
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val cols = line.split(",")
                    if (cols.size >= 4) {
                        val hexCode = cols[0].trim()
                        val stationName = cols[3].trim()
                        if (hexCode.isNotEmpty() && stationName.isNotEmpty()) {
                            map[hexCode.lowercase(Locale.getDefault())] = stationName
                        }
                    }
                }
        }
        return map
    }

    private fun lookupStationNameByHexCode(hexCode: String): String? {
        val currentStationMap = stationMap ?: return null
        return currentStationMap[hexCode.lowercase(Locale.getDefault())]
    }

    internal fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices.reversed()) {
            val b = bytes[i].toInt() and 0xff
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
            if (i > 0) {
                sb.append(" ")
            }
        }
        return sb.toString()
    }

    internal fun toReversedHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            if (i > 0) {
                sb.append(" ")
            }
            val b = bytes[i].toInt() and 0xff
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
        }
        return sb.toString()
    }
}
