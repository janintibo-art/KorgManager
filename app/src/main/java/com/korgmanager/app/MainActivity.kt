package com.korgmanager.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Fichiers utilisés par l'Electribe ES-1 mkII sur SmartMedia
    private val es1Types = mapOf(
        "es1" to "Banque ES-1 (samples + patterns + songs)",
        "wav" to "Sample WAV",
        "aif" to "Sample AIFF",
        "aiff" to "Sample AIFF",
        "txt" to "names.txt (noms d'origine)"
    )

    /** Un fichier affiché : soit un DocumentFile (dossier Android), soit une FatEntry (carte USB). */
    private data class Item(val name: String, val size: Long, val doc: DocumentFile?, val fat: FatEntry?)

    private var treeUri: Uri? = null
    private var fatFs: FatFs? = null
    private var usbReader: UsbCardReader? = null
    private val usbMode: Boolean get() = fatFs != null

    private var allItems: List<Item> = emptyList()
    private var soundNames: MutableMap<String, String> = mutableMapOf()
    private var shown: List<Item> = emptyList()
    private var showAll = false

    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private var player: MediaPlayer? = null

    private val usbPermissionAction = "com.korgmanager.app.USB_PERMISSION"

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importAudio(uri)
        }

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                closeUsb()
                treeUri = uri
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putString("tree", uri.toString()).apply()
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.fileList)
        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.pickButton).setOnClickListener { openTree.launch(null) }
        findViewById<Button>(R.id.usbButton).setOnClickListener { openUsbCard() }
        findViewById<Button>(R.id.addButton).setOnClickListener {
            if (fatFs == null && treeUri == null) toast(R.string.msg_pick_first)
            else pickAudio.launch("audio/*")
        }

        findViewById<Switch>(R.id.showAllSwitch).setOnCheckedChangeListener { _, checked ->
            showAll = checked
            applyFilter()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            showFileMenu(shown[position])
        }

        // Tutoriel au premier lancement
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (!prefs.getBoolean("help_shown", false)) {
            prefs.edit().putBoolean("help_shown", true).apply()
            showHelp()
        }

        // Restaurer le dernier dossier choisi
        getPreferences(Context.MODE_PRIVATE).getString("tree", null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                treeUri = uri
                refresh()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_help) { showHelp(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.help_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        stopPlayer()
        closeUsb()
        super.onDestroy()
    }

    // ==================== ACCÈS USB DIRECT À LA CARTE ====================

    private fun closeUsb() {
        fatFs = null
        usbReader?.close()
        usbReader = null
    }

    private fun openUsbCard() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == 8 }
        }
        if (device == null) {
            toast(R.string.usb_none)
            return
        }
        if (manager.hasPermission(device)) {
            mountUsb(manager, device)
            return
        }
        // Demander la permission USB à Android
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    mountUsb(manager, device)
                } else {
                    toast(R.string.usb_denied)
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(usbPermissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(usbPermissionAction).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE
        )
        manager.requestPermission(device, pi)
    }

    private fun mountUsb(manager: UsbManager, device: UsbDevice) {
        statusText.text = getString(R.string.usb_opening)
        Thread {
            try {
                closeUsb()
                val reader = UsbCardReader(manager, device)
                reader.open()
                val fs = FatFs(reader)
                usbReader = reader
                runOnUiThread {
                    fatFs = fs
                    refresh()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.usb_button)
                        .setMessage(getString(R.string.usb_backup_warn, fs.typeName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    closeUsb()
                    statusText.text = getString(R.string.usb_error, e.message ?: "?")
                }
            }
        }.start()
    }

    // ==================== LISTE ET FILTRES ====================

    private fun refresh() {
        val fs = fatFs
        if (fs != null) {
            Thread {
                try {
                    val items = fs.list().map { Item(it.name, it.size, null, it) }
                    soundNames = parseNamesFile(items)
                    val free = fs.freeSpace()
                    runOnUiThread {
                        allItems = items
                        applyFilter()
                        statusText.append("\n" + getString(
                            R.string.usb_status,
                            fs.typeName,
                            Formatter.formatShortFileSize(this, free)
                        ))
                    }
                } catch (e: Exception) {
                    runOnUiThread { statusText.text = getString(R.string.usb_error, e.message ?: "?") }
                }
            }.start()
            return
        }
        val uri = treeUri ?: return
        val dir = DocumentFile.fromTreeUri(this, uri)
        if (dir == null || !dir.isDirectory) {
            statusText.text = getString(R.string.status_error)
            return
        }
        allItems = dir.listFiles()
            .filter { it.isFile }
            .map { Item(it.name ?: "?", it.length(), it, null) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        applyFilter()
        Thread {
            soundNames = parseNamesFile(allItems)
            runOnUiThread { applyFilter() }
        }.start()
    }

    /** Lit names.txt (s'il existe) : lignes "00.WAV=nom du son". */
    private fun parseNamesFile(items: List<Item>): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        val txt = items.firstOrNull { it.name.equals("names.txt", ignoreCase = true) } ?: return map
        try {
            val text = String(readItemBytes(txt), Charsets.ISO_8859_1)
            for (line in text.lines()) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val sep = if (l.contains('=')) l.indexOf('=') else l.indexOf(' ')
                if (sep <= 0) continue
                val key = l.substring(0, sep).trim().uppercase(Locale.ROOT)
                val value = l.substring(sep + 1).trim()
                if (value.isNotEmpty()) map[key] = value
            }
        } catch (e: Exception) { /* fichier illisible */ }
        return map
    }

    /** Réécrit names.txt sur la carte ou dans le dossier. */
    private fun saveNamesFile() {
        val text = soundNames.entries
            .sortedBy { it.key }
            .joinToString("\r\n") { "${it.key}=${it.value}" } + "\r\n"
        writeToDestination("NAMES.TXT", text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun isSample(name: String): Boolean =
        extensionOf(name) in setOf("wav", "aif", "aiff")

    private fun sampleNameOk(name: String): Boolean {
        val base = name.substringBeforeLast('.')
        return base.length == 2 && base.all { it.isDigit() }
    }

    private fun applyFilter() {
        shown = if (showAll) allItems
        else allItems.filter { es1Types.containsKey(extensionOf(it.name)) }

        val warnCount = allItems.count { isSample(it.name) && !sampleNameOk(it.name) }
        val tooMany = allItems.size > 100

        val sb = StringBuilder(getString(R.string.status_count, shown.size))
        if (warnCount > 0) sb.append("\n").append(getString(R.string.status_badnames, warnCount))
        if (tooMany) sb.append("\n").append(getString(R.string.status_toomany, allItems.size))
        statusText.text = sb.toString()

        listView.adapter = object : ArrayAdapter<Item>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, shown
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val it = shown[position]
                var type = es1Types[extensionOf(it.name)] ?: getString(R.string.type_other)
                if (isSample(it.name) && !sampleNameOk(it.name)) {
                    type = "⚠ " + getString(R.string.warn_name) + " — " + type
                }
                soundNames[it.name.uppercase(Locale.ROOT)]?.let { friendly ->
                    type = "🏷 $friendly · $type"
                }
                v.findViewById<TextView>(android.R.id.text1).text = it.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "$type — ${Formatter.formatShortFileSize(context, it.size)}"
                return v
            }
        }
    }

    // ==================== LECTURE DES FICHIERS (les 2 modes) ====================

    /** Lit un fichier complet en mémoire, quel que soit le mode. */
    private fun readItemBytes(item: Item): ByteArray {
        item.fat?.let { return fatFs!!.readFile(it) }
        item.doc?.let { doc ->
            contentResolver.openInputStream(doc.uri)?.use { return it.readBytes() }
        }
        return ByteArray(0)
    }

    /** Copie un fichier vers le cache local et renvoie le File. */
    private fun itemToCache(item: Item): File {
        val f = File(cacheDir, item.name.replace('/', '_'))
        f.writeBytes(readItemBytes(item))
        return f
    }

    // ==================== MENU FICHIER ====================

    private fun showFileMenu(item: Item) {
        val ext = extensionOf(item.name)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isSample(item.name)) {
            actions.add(getString(R.string.action_play) to { playItem(item) })
            actions.add(getString(R.string.action_convert) to { processItem(item, normalize = false) })
            actions.add(getString(R.string.action_normalize) to { processItem(item, normalize = true) })
            actions.add(getString(R.string.action_setname) to { setNameDialog(item) })
        }
        if (ext == "txt") {
            actions.add(getString(R.string.action_read) to { showTextFile(item) })
        }
        if (ext == "es1") {
            actions.add(getString(R.string.action_extract) to { extractBank(item) })
            actions.add(getString(R.string.action_analyze) to { analyzeBank(item) })
        }
        actions.add(getString(R.string.action_info) to { showInfo(item) })
        actions.add(getString(R.string.action_rename) to { renameDialog(item) })
        actions.add(getString(R.string.action_delete) to { deleteDialog(item) })

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .show()
    }

    private fun playItem(item: Item) {
        Thread {
            try {
                val f = itemToCache(item)
                runOnUiThread {
                    playLocalFile(f)
                    Toast.makeText(this, getString(R.string.msg_playing, item.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.msg_play_error) }
            }
        }.start()
    }

    private fun playLocalFile(file: File) {
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopPlayer() }
                prepare()
            }
        } catch (e: Exception) {
            stopPlayer()
            toast(R.string.msg_play_error)
        }
    }

    private fun stopPlayer() {
        player?.release()
        player = null
    }

    private fun showTextFile(item: Item) {
        Thread {
            val text = try {
                String(readItemBytes(item), Charsets.ISO_8859_1).take(8000)
            } catch (e: Exception) {
                getString(R.string.msg_read_error)
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(item.name)
                    .setMessage(if (text.isBlank()) getString(R.string.msg_empty_file) else text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun showInfo(item: Item) {
        Thread {
            val sb = StringBuilder()
            val type = es1Types[extensionOf(item.name)] ?: getString(R.string.type_other)
            sb.append(getString(R.string.info_body_short, type,
                Formatter.formatFileSize(this, item.size)))
            if (extensionOf(item.name) == "wav") {
                try {
                    val head = readItemBytes(item)
                    parseWavHeader(head)?.let { (rate, bits, channels) ->
                        sb.append("\n\n").append(getString(R.string.info_wav, rate, bits,
                            if (channels == 1) "mono" else "stéréo"))
                        if (rate != 32000) sb.append("\n").append(getString(R.string.warn_rate))
                        if (bits != 8 && bits != 16) sb.append("\n").append(getString(R.string.warn_bits))
                    }
                } catch (e: Exception) { /* pas d'analyse */ }
            }
            if (isSample(item.name) && !sampleNameOk(item.name)) {
                sb.append("\n\n").append(getString(R.string.warn_name_long))
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(item.name)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun parseWavHeader(b: ByteArray): Triple<Int, Int, Int>? {
        if (b.size < 36) return null
        if (String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val size = le32(b, pos + 4)
            if (id == "fmt ") {
                if (pos + 24 > b.size) return null
                val channels = le16(b, pos + 10)
                val rate = le32(b, pos + 12)
                val bits = le16(b, pos + 22)
                return Triple(rate, bits, channels)
            }
            pos += 8 + size + (size % 2)
        }
        return null
    }

    /** Donne un vrai nom au son (stocké dans names.txt, le fichier garde son numéro). */
    private fun setNameDialog(item: Item) {
        val key = item.name.uppercase(Locale.ROOT)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(soundNames[key] ?: "")
            hint = getString(R.string.setname_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setname_title, item.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) soundNames.remove(key) else soundNames[key] = value
                Thread {
                    try {
                        saveNamesFile()
                        runOnUiThread { toast(R.string.msg_saved); refresh() }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message ?: getString(R.string.msg_failed),
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameDialog(item: Item) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(item.name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) { toast(R.string.msg_failed); return@setPositiveButton }
                Thread {
                    val ok = try {
                        if (item.fat != null) { fatFs!!.rename(item.fat, newName); true }
                        else item.doc?.renameTo(newName) == true
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message ?: getString(R.string.msg_failed),
                                Toast.LENGTH_LONG).show()
                        }
                        false
                    }
                    runOnUiThread {
                        if (ok) toast(R.string.msg_renamed)
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteDialog(item: Item) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, item.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Thread {
                    val ok = try {
                        if (item.fat != null) { fatFs!!.delete(item.fat); true }
                        else item.doc?.delete() == true
                    } catch (e: Exception) { false }
                    runOnUiThread {
                        toast(if (ok) R.string.msg_deleted else R.string.msg_failed)
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ==================== AJOUT ET CONVERSION DE SONS ====================

    /** Premier nom libre au format ES-1 : 00.WAV à 99.WAV. */
    private fun nextFreeName(): String? {
        val used = allItems.map { it.name.substringBeforeLast('.').uppercase(Locale.ROOT) }.toSet()
        for (n in 0..99) {
            val base = String.format(Locale.ROOT, "%02d", n)
            if (base !in used) return "$base.WAV"
        }
        return null
    }

    /** Écrit un fichier sur la carte USB ou dans le dossier choisi. */
    private fun writeToDestination(name: String, data: ByteArray) {
        val fs = fatFs
        if (fs != null) {
            fs.writeFile(name, data)
        } else {
            val dir = treeUri?.let { DocumentFile.fromTreeUri(this, it) }
                ?: throw java.io.IOException(getString(R.string.msg_no_folder))
            dir.findFile(name)?.delete()
            val mime = if (name.uppercase(Locale.ROOT).endsWith(".TXT")) "text/plain" else "audio/wav"
            val dest = dir.createFile(mime, name)
                ?: throw java.io.IOException(getString(R.string.msg_failed))
            contentResolver.openOutputStream(dest.uri)?.use { it.write(data) }
        }
    }

    /** Importe un audio du téléphone, le convertit au format ES-1 et l'écrit. */
    private fun importAudio(uri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.msg_converting))
            .setCancelable(false).create()
        progress.show()
        Thread {
            try {
                val tmp = File(cacheDir, "import.bin")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val decoded = AudioConvert.decode(tmp.absolutePath)
                tmp.delete()
                val converted = AudioConvert.resample(decoded, AudioConvert.ES1_RATE)
                val wav = AudioConvert.toWav(converted)
                runOnUiThread {
                    progress.dismiss()
                    askNameAndSave(wav, converted.durationSec)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, getString(R.string.msg_decode_error,
                        e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun askNameAndSave(wav: ByteArray, durationSec: Double) {
        val default = nextFreeName()
        if (default == null) { toast(R.string.msg_no_slot); return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(default)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_title,
                Formatter.formatShortFileSize(this, wav.size.toLong()),
                String.format(Locale.ROOT, "%.1f", durationSec)))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                Thread {
                    try {
                        writeToDestination(name, wav)
                        runOnUiThread { toast(R.string.msg_saved); refresh() }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message ?: getString(R.string.msg_failed),
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Convertit (32 kHz) ou normalise un sample existant, réécrit sur place. */
    private fun processItem(item: Item, normalize: Boolean) {
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.msg_converting))
            .setCancelable(false).create()
        progress.show()
        Thread {
            try {
                val cached = itemToCache(item)
                var audio = AudioConvert.decode(cached.absolutePath)
                audio = AudioConvert.resample(audio, AudioConvert.ES1_RATE)
                if (normalize) audio = AudioConvert.normalize(audio)
                val wav = AudioConvert.toWav(audio)
                writeToDestination(item.name, wav)
                runOnUiThread {
                    progress.dismiss()
                    toast(R.string.msg_saved)
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, getString(R.string.msg_decode_error,
                        e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ==================== BANQUES .ES1 ====================

    private fun extractBank(item: Item) {
        val progress = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.msg_extracting))
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            var output = ""
            var wavs: List<File> = emptyList()
            try {
                val bankCopy = File(cacheDir, "bank.ES1")
                bankCopy.writeBytes(readItemBytes(item))
                val outBase = File(filesDir, "extracted")
                outBase.deleteRecursively()
                outBase.mkdirs()
                // Le décodeur exige un dossier de sortie qui n'existe pas encore : il le crée
                val outDir = File(outBase, "out")
                val exe = File(applicationInfo.nativeLibraryDir, "libes12wav.so")
                val proc = ProcessBuilder(exe.absolutePath, bankCopy.absolutePath, outDir.absolutePath)
                    .directory(outBase)
                    .redirectErrorStream(true)
                    .start()
                output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                bankCopy.delete()
                val searchDir = if (outDir.isDirectory) outDir else outBase
                wavs = (searchDir.walkTopDown().toList())
                    .filter { it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".wav") }
                    .sortedBy { it.name }
            } catch (e: Exception) {
                output += "\n" + (e.message ?: e.toString())
            }
            runOnUiThread {
                progress.dismiss()
                if (wavs.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(item.name)
                        .setMessage(getString(R.string.msg_no_wav) + "\n\n" + output.take(3000))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    showExtractedList(wavs)
                }
            }
        }.start()
    }

    /**
     * Fenêtre des samples extraits : chaque ligne a un bouton ▶ pour écouter
     * et un champ pour nommer le son. « Exporter » écrit les WAV sur la carte
     * et enregistre tous les noms dans NAMES.TXT.
     */
    private fun showExtractedList(wavs: List<File>) {
        val view = layoutInflater.inflate(R.layout.dialog_samples, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.rowsContainer)
        val fields = mutableListOf<EditText>()
        val targetNames = mutableListOf<String>()

        // Numéros de destination : on garde ceux du fichier extrait s'ils sont
        // au format 00-99, sinon on numérote à la suite.
        for ((index, w) in wavs.withIndex()) {
            val base = w.name.substringBeforeLast('.')
            val target = if (base.length == 2 && base.all { it.isDigit() }) "$base.WAV"
                         else String.format(Locale.ROOT, "%02d.WAV", index)
            targetNames.add(target)

            val row = layoutInflater.inflate(R.layout.row_sample, container, false)
            row.findViewById<TextView>(R.id.fileLabel).text =
                "$target — ${Formatter.formatShortFileSize(this, w.length())}"
            val field = row.findViewById<EditText>(R.id.nameField)
            field.setText(soundNames[target.uppercase(Locale.ROOT)] ?: "")
            fields.add(field)
            row.findViewById<Button>(R.id.playBtn).setOnClickListener { playLocalFile(w) }
            container.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.extracted_title, wavs.size))
            .setView(view)
            .setPositiveButton(R.string.action_export) { _, _ ->
                for (i in wavs.indices) {
                    val value = fields[i].text.toString().trim()
                    val key = targetNames[i].uppercase(Locale.ROOT)
                    if (value.isEmpty()) soundNames.remove(key) else soundNames[key] = value
                }
                exportWavs(wavs, targetNames)
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /** Exporte les WAV extraits (renommés 00.WAV…) puis enregistre NAMES.TXT. */
    private fun exportWavs(wavs: List<File>, targetNames: List<String>) {
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.msg_exporting))
            .setCancelable(false).create()
        progress.show()
        Thread {
            var ok = 0
            var error: String? = null
            try {
                for (i in wavs.indices) {
                    writeToDestination(targetNames[i], wavs[i].readBytes())
                    ok++
                }
                if (soundNames.isNotEmpty()) saveNamesFile()
            } catch (e: Exception) {
                error = e.message
            }
            val done = ok
            val err = error
            runOnUiThread {
                progress.dismiss()
                val msg = if (err == null) getString(R.string.msg_exported, done)
                          else getString(R.string.msg_exported, done) + "\n" + err
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun analyzeBank(item: Item) {
        Thread {
            val sb = StringBuilder()
            try {
                val head = readItemBytes(item)
                if (head.size >= 4 && String(head, 0, 4) == "KORG") {
                    sb.append(getString(R.string.bank_valid))
                } else {
                    sb.append(getString(R.string.bank_invalid))
                }
            } catch (e: Exception) {
                sb.append(getString(R.string.msg_read_error))
            }
            sb.append("\n\n")
            sb.append(getString(R.string.bank_size, Formatter.formatFileSize(this, item.size)))
            sb.append("\n\n")
            sb.append(getString(R.string.bank_explain))
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(item.name)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    // ==================== UTILITAIRES ====================

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        le16(b, o) or (le16(b, o + 2) shl 16)

    private fun toast(res: Int) =
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
