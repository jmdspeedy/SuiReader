package com.jmdspeedy.suireader

import android.app.PendingIntent
import android.content.Intent
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager.beginDelayedTransition
import androidx.transition.TransitionSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.text.NumberFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var historyList: LinearLayout? = null
    private var nfcAdapter: NfcAdapter? = null
    private var balanceText: TextView? = null
    private var initialScanView: View? = null
    private var suicaDataView: View? = null
    private var historyTitle: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        historyList = findViewById(R.id.list)
        balanceText = findViewById(R.id.balance_text)
        initialScanView = findViewById(R.id.initial_scan_view)
        suicaDataView = findViewById(R.id.suica_data_view)
        historyTitle = findViewById(R.id.history_title)

        // Initialize the Suica station map
        Suica.init(this)

        resolveIntent(intent)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
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
                updateUi(suicaData)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("No Data Found")
                    .setMessage("Could not read data from this card.")
                    .setPositiveButton("Okay") { _, _ -> }.show()
            }
        }
    }

    private fun crossfadeViews(inView: View, outView: View) {
        val suicaCard = findViewById<CardView>(R.id.suica_card)

        val transition = TransitionSet().apply {
            // For the smooth resizing of the card
            addTransition(ChangeBounds())
            // For the fading of the content
            addTransition(Fade())
            duration = 350 // A slightly longer duration feels smoother
            interpolator = AccelerateDecelerateInterpolator()
        }

        beginDelayedTransition(suicaCard, transition)

        // After setting up the transition, simply change the visibility.
        // The TransitionManager will animate the changes.
        outView.visibility = View.GONE
        inView.visibility = View.VISIBLE
    }

    private fun updateUi(suicaData: SuicaData) {
        // Animate from initial view to data view
        crossfadeViews(suicaDataView!!, initialScanView!!)
        historyTitle?.visibility = View.VISIBLE

        // Update card info
        val formattedBalance = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(suicaData.balance)
        balanceText?.text = formattedBalance

        // Clear old history
        historyList?.removeAllViews()

        // Populate history
        val inflater = LayoutInflater.from(this)
        if (suicaData.transactionHistory.isEmpty()) {
            val noHistoryView = TextView(this)
            noHistoryView.text = "No history found."
            historyList?.addView(noHistoryView)
        } else {
            suicaData.transactionHistory.forEach { block ->
                val historyView = inflater.inflate(R.layout.history_item, historyList, false)
                val icon = historyView.findViewById<ImageView>(R.id.icon)
                val dateText = historyView.findViewById<TextView>(R.id.date_text)
                val descriptionText = historyView.findViewById<TextView>(R.id.description_text)
                val amountText = historyView.findViewById<TextView>(R.id.amount_text)

                dateText.text = block.date

                // Set icon and description based on process type
                when (block.processType) {
                    "Charge", "Auto-charge" -> {
                        icon.setImageResource(android.R.drawable.ic_input_add)
                        descriptionText.text = block.processType
                        amountText.text = "+${NumberFormat.getCurrencyInstance(Locale.JAPAN).format(block.balance)}"
//                        amountText.setTextColor(resources.getColor(R.color.teal_700, theme))
                    }
                    "Bus (PiTaPa?)", "Bus (IruCa?)" -> {
//                        icon.setImageResource(android.R.drawable.ic_menu_directions_bus)
                        descriptionText.text = "Local Bus"
                        amountText.text = "-¥${block.balance}" // Assuming bus rides are deductions
                    }
                    "Vending machine/POS", "Vending machine" -> {
                        icon.setImageResource(android.R.drawable.ic_menu_crop)
                        descriptionText.text = "Purchase"
                         amountText.text = "-¥${block.balance}"
                    }
                    else -> { // Train icon for most other cases
//                        icon.setImageResource(android.R.drawable.ic_menu_directions_railway)
                        descriptionText.text = "${block.entryStationName ?: "Station"} → ${block.exitStationName ?: "Station"}"
                        amountText.text = "-¥${block.balance}"
                    }
                }

                historyList?.addView(historyView)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_main_clear -> {
                // Return to initial state
                crossfadeViews(initialScanView!!, suicaDataView!!)
                historyTitle?.visibility = View.GONE
                historyList?.removeAllViews()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
