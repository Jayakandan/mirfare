package com.example.mifareamount

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    private var status by mutableStateOf("Pick a mode, then tap the card")
    private var uid by mutableStateOf("-")
    private var amountOnCard by mutableStateOf<Int?>(null)
    private var previousOnCard by mutableStateOf<Int?>(null)
    private var keyUsed by mutableStateOf<String?>(null)
    private var lastOk by mutableStateOf<Boolean?>(null)
    private var amountInput by mutableStateOf("")
    private var keyInput by mutableStateOf("")            // sector key, hex (optional)
    private var nameInput by mutableStateOf("")           // cardholder name -> block 5
    private var nameOnCard by mutableStateOf<String?>(null)
    private var mode by mutableStateOf(Mode.TOPUP)

    enum class Mode { READ, WRITE, TOPUP }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        if (adapter == null) { status = "This phone has no NFC"; return }
        if (!adapter.isEnabled) { status = "Turn on NFC in settings"; return }
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, flag)
        adapter.enableForegroundDispatch(this, pi, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) return

        uid = MifareAmount.uidHex(tag)
        val key = keyInput.ifBlank { null }

        val r = when (mode) {
            Mode.READ -> MifareAmount.readAmount(tag, key)
            Mode.WRITE -> {
                val amt = amountInput.toIntOrNull()
                if (amt == null) { fail("Enter a valid whole number first"); return }
                MifareAmount.writeAmount(tag, amt, key, nameInput)
            }
            Mode.TOPUP -> {
                val delta = amountInput.toIntOrNull()
                if (delta == null) { fail("Enter the amount to add first"); return }
                MifareAmount.topUp(tag, delta, key, nameInput)
            }
        }
        status = r.message
        lastOk = r.ok
        r.amount?.let { amountOnCard = it }
        previousOnCard = r.previous
        r.keyUsed?.let { keyUsed = it }
        nameOnCard = r.name
        // Clear the input after a successful top-up so the next tap can't double-charge.
        if (r.ok && mode == Mode.TOPUP) amountInput = ""
    }

    private fun fail(msg: String) { status = msg; lastOk = false }

    /** Allow digits with at most one leading '-' (so "1-2" can never be typed). */
    private fun sanitize(raw: String): String {
        val neg = raw.startsWith("-")
        val digits = raw.filter { it.isDigit() }.take(10)
        return if (neg) "-$digits" else digits
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("MIFARE top-up", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("sector 1 / block 4", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))

            // ---- card state ----
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Card UID", fontSize = 12.sp)
                    Text(uid, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Cardholder", fontSize = 12.sp)
                    Text(nameOnCard ?: "-", fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Balance on card", fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val prev = previousOnCard
                        val now = amountOnCard
                        if (prev != null && now != null && prev != now) {
                            Text("$prev", fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.outline)
                            Text("  ->  ", fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.outline)
                        }
                        Text(now?.toString() ?: "-",
                            fontWeight = FontWeight.Medium, fontSize = 30.sp)
                    }
                    keyUsed?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("unlocked with key $it",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // ---- mode ----
            Spacer(Modifier.height(16.dp))
            Text("Mode", fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Read", Mode.READ)
                ModeChip("Top-up", Mode.TOPUP)
                ModeChip("Set", Mode.WRITE)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (mode) {
                    Mode.READ  -> "Tap the card to read the stored balance."
                    Mode.TOPUP -> "The amount below is ADDED to the balance already on the card."
                    Mode.WRITE -> "Overwrites the balance with exactly the amount below."
                },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
            )

            // ---- amount + presets (hidden in READ mode) ----
            if (mode != Mode.READ) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = sanitize(it) },
                    label = {
                        Text(if (mode == Mode.TOPUP) "Amount to add (- to deduct)"
                             else "Exact amount to store")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == Mode.TOPUP) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 50, 100, 500).forEach { v ->
                            AssistChip(
                                onClick = {
                                    val cur = amountInput.toIntOrNull() ?: 0
                                    amountInput = (cur + v).toString()
                                },
                                label = { Text("+$v") }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { amountInput = "" }) { Text("Clear") }
                }
            }

            // ---- cardholder name (block 5) ----
            if (mode != Mode.READ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        nameInput = it.filter { c -> c.code in 32..126 }
                                      .take(MifareAmount.NAME_MAX_LEN)
                    },
                    label = { Text("Cardholder name (blank = leave unchanged)") },
                    supportingText = {
                        Text("${nameInput.length}/${MifareAmount.NAME_MAX_LEN} - written to block 5")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- optional key ----
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Sector key (12 hex, blank = try defaults)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- status ----
            Spacer(Modifier.height(20.dp))
            Text("Status", fontSize = 12.sp)
            Text(
                status,
                fontSize = 15.sp,
                color = when (lastOk) {
                    true  -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    else  -> MaterialTheme.colorScheme.onSurface
                }
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "Type the amount BEFORE tapping. Hold the card flat against the back of " +
                "the phone and keep it still until the status updates.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun ModeChip(label: String, m: Mode) {
        FilterChip(
            selected = mode == m,
            onClick = {
                mode = m
                previousOnCard = null
                lastOk = null
                status = when (m) {
                    Mode.READ  -> "Tap the card to read it"
                    Mode.TOPUP -> "Enter an amount, then tap the card"
                    Mode.WRITE -> "Enter the exact amount, then tap the card"
                }
            },
            label = { Text(label) }
        )
    }
}
