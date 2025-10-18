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

        // Initialize the Suica station map
        Suica.init(this)

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

    private fun isJapaneseICTag(tag: Tag): Boolean {
        if (!tag.techList.any { it.endsWith("NfcF") }) {
            return false
        }
        val nfcF = NfcF.get(tag)
        return try {
            nfcF.connect()
            val systemCode = Suica.toHex(nfcF.systemCode)
            val isJapaneseIC = systemCode == "03 00"
            nfcF.close()
            isJapaneseIC
        } catch (e: IOException) {
            Log.e("MainActivity", "Could not connect to FeliCa card to check system code.", e)
            if (nfcF.isConnected) nfcF.close()
            false
        }
    }

    private fun resolveIntent(intent: Intent) {
        val validActions = listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        if (intent.action in validActions) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return

            if (!isJapaneseICTag(tag)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Card Not Supported")
                    .setMessage("This is not a Japanese IC card.")
                    .setPositiveButton("Okay") { _, _ -> }.show()
                return
            }

            val suicaData = Suica.extractData(tag)

            if (suicaData != null) {
                val payload = dumpTagData(suicaData).toByteArray()
                val record = NdefRecord(NdefRecord.TNF_UNKNOWN, byteArrayOf(), tag.id, payload)
                val msg = NdefMessage(arrayOf(record))
                buildTagViews(listOf(msg))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("No Data Found")
                    .setMessage("This card does not contain any data")
                    .setPositiveButton("Okay") { _, _ -> }.show()
            }
        }
    }

    private fun dumpTagData(suicaData: SuicaData): String {
        val sb = StringBuilder()
        sb.appendLine("Card ID: ${suicaData.cardId}")
        sb.appendLine("Balance: ¥${suicaData.balance ?: "N/A"}")
        sb.appendLine("\nTechnologies: FeliCa")
        sb.appendLine("  Manufacturer: ${suicaData.manufacturer}")
        sb.appendLine("  System Code: ${suicaData.systemCode}")
        sb.appendLine("\n  IC Card History:")
        if (suicaData.transactionHistory.isEmpty()) {
            sb.appendLine("    No history found.")
        } else {
            suicaData.transactionHistory.forEachIndexed { i, block ->
                sb.appendLine("    Block $i:")
                sb.appendLine("      Console: ${block.consoleType}, Process: ${block.processType}")
                sb.appendLine("      Date: ${block.date}, Balance: ¥${block.balance}")
                if (block.entryStationCode != null || block.exitStationCode != null) {
                    sb.appendLine("      Entry Station: ${block.entryStationCode ?: "-"}, Exit Station: ${block.exitStationCode ?: "-"}")
                }
                sb.appendLine("      Entry Station Name: ${block.entryStationName ?: "-"}, Exit Station Name: ${block.exitStationName ?: "-"}")
            }
        }
        return sb.toString()
    }


    private fun buildTagViews(msgs: List<NdefMessage>) {
        if (msgs.isEmpty()) {
            return
        }
        val inflater = LayoutInflater.from(this)
        val content = tagList

        // Clear existing views
        clearTags()

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
        tagList?.removeAllViews()
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat.getDateTimeInstance()
    }
}
