package com.jmdspeedy.suireader

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jmdspeedy.suireader.record.ParsedNdefRecord
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var tagList: LinearLayout? = null
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tagList = findViewById<View>(R.id.list) as LinearLayout
        resolveIntent(intent)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // for virtual device testing, comment this out
//        if (nfcAdapter == null) {
//            showNoNfcDialog()
//            return
//        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter?.isEnabled == false) {
            openNfcSettings()
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveIntent(intent)
    }

    private fun showNoNfcDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.no_nfc)
            .setNeutralButton(R.string.close_app) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openNfcSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_NFC)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        startActivity(intent)
    }

    private fun resolveIntent(intent: Intent) {
        val validActions = listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        if (intent.action in validActions) {
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            val messages = mutableListOf<NdefMessage>()
            val empty = ByteArray(0)
            val id = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            var isSuica = false
            for (tech in tag.techList) {
                // If card is a Felica
                if (tech == NfcF::class.java.name) {
                    val nfcF = NfcF.get(tag)
                    val systemCode = toHex(nfcF.systemCode)
                    if (systemCode == "03 00") {
                        isSuica = true
                    }
                }
            }
            Log.d("MainActivity", "isSuica: $isSuica")
            if (isSuica) {
                val payload = dumpTagData(tag).toByteArray()
                val record = NdefRecord(NdefRecord.TNF_UNKNOWN, empty, id, payload)
                val msg = NdefMessage(arrayOf(record))
                messages.add(msg)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Wrong Card Type")
                    .setMessage("This is not a Japanese IC card")
                    .setPositiveButton("Okay") { dialog, which ->
                    }.show()
            }
            buildTagViews(messages)
        }
    }

    private fun dumpTagData(tag: Tag): String {
        val sb = StringBuilder()
        val id = tag.id
        sb.append("ID (hex): ").append(toHex(id)).append('\n')
        sb.append("ID (reversed hex): ").append(toReversedHex(id)).append('\n')
        sb.append("ID (dec): ").append(toDec(id)).append('\n')
        sb.append("ID (reversed dec): ").append(toReversedDec(id)).append('\n')
        val prefix = "android.nfc.tech."
        sb.append("Technologies: ")
        for (tech in tag.techList) {
            sb.append(tech.substring(prefix.length))
            sb.append(", ")
        }
        sb.delete(sb.length - 2, sb.length)
        sb.append(extractFelica(tag))
        return sb.toString()
    }

    private fun extractFelica(tag: Tag): String {
        val sb = StringBuilder()
        val nfcF = NfcF.get(tag)
        try {
            nfcF.connect()
            val systemCode = toHex(nfcF.systemCode)
            sb.appendLine("\nNFC-F / FeliCa:")
            sb.appendLine("  Manufacturer: ${toHex(nfcF.manufacturer)}")
            sb.appendLine("  System Code: $systemCode")

            //Read History
            sb.append(readSuicaHistory(nfcF))

        } catch (e: IOException) {
            sb.appendLine("\nNFC-F / FeliCa error: ${e.message}")
        } finally {
            if (nfcF.isConnected) {
                nfcF.close()
            }
        }
        return sb.toString()
    }

    private fun readSuicaHistory(nfcF: NfcF): String {
        val sb = StringBuilder()
        sb.appendLine("  IC Card History:")
        try {
            val idm = nfcF.tag.id
            val serviceCode = 0x090f
            val numBlocks = 20

            // Read 20 history blocks
            for (i in 0 until numBlocks) {
                val cmdPacket = mutableListOf<Byte>()
                cmdPacket.add(0x06) // Command: Read Without Encryption
                cmdPacket.addAll(idm.toList())
                cmdPacket.add(1) // Number of Services
                // Service Code (little-endian)
                cmdPacket.add((serviceCode and 0xFF).toByte())
                cmdPacket.add((serviceCode shr 8 and 0xFF).toByte())
                cmdPacket.add(1) // Number of Blocks to read
                cmdPacket.add(0x80.toByte()) // 2-byte block list element
                cmdPacket.add(i.toByte())    // block number

                // Prepend the length byte
                val readCmd = ByteArray(cmdPacket.size + 1)
                readCmd[0] = (cmdPacket.size + 1).toByte()
                System.arraycopy(cmdPacket.toByteArray(), 0, readCmd, 1, cmdPacket.size)

                val response = nfcF.transceive(readCmd)

                // Response[10] is status flag 1, 0x00 means success
                if (response.size > 12 && response[10] == 0x00.toByte()) {
                    // Block data starts at byte 13 and is 16 bytes long
                    val blockData = response.copyOfRange(13, 13 + 16)
                    // For now, just dumping the raw hex data as requested.
                    sb.appendLine("    Block $i: ${toHex(blockData)}")
                } else {
                    sb.appendLine("    Block $i: Read failed")
                    break
                }
            }
        } catch (e: IOException) {
            sb.appendLine("    Error reading history: ${e.message}")
        }
        return sb.toString()
    }

    private fun toHex(bytes: ByteArray): String {
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

    private fun toReversedHex(bytes: ByteArray): String {
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

    private fun toDec(bytes: ByteArray): Long {
        var result: Long = 0
        var factor: Long = 1
        for (i in bytes.indices) {
            val value = bytes[i].toLong() and 0xffL
            result += value * factor
            factor *= 256L
        }
        return result
    }

    private fun toReversedDec(bytes: ByteArray): Long {
        var result: Long = 0
        var factor: Long = 1
        for (i in bytes.indices.reversed()) {
            val value = bytes[i].toLong() and 0xffL
            result += value * factor
            factor *= 256L
        }
        return result
    }

    private fun buildTagViews(msgs: List<NdefMessage>) {
        if (msgs.isEmpty()) {
            return
        }
        val inflater = LayoutInflater.from(this)
        val content = tagList

        // Parse the first message in the list
        // Build views for all of the sub records
        val now = Date()
        val records = NdefMessageParser.parse(msgs[0])
        val size = records.size
        for (i in 0 until size) {
            val timeView = TextView(this)
            timeView.text = TIME_FORMAT.format(now)
            content!!.addView(timeView, 0)
            val record: ParsedNdefRecord = records[i]
            content.addView(record.getView(this, inflater, content, i), 1 + i)
            content.addView(inflater.inflate(R.layout.tag_divider, content, false), 2 + i)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_main_clear -> {
                clearTags()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun clearTags() {
        for (i in tagList!!.childCount - 1 downTo 0) {
            val view = tagList!!.getChildAt(i)
            if (view.id != R.id.tag_viewer_text) {
                tagList!!.removeViewAt(i)
            }
        }
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat.getDateTimeInstance()
    }
}
