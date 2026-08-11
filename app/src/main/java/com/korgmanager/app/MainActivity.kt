package com.korgmanager.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import androidx.documentfile.provider.DocumentFile
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

    private var treeUri: Uri? = null
    private var allFiles: List<DocumentFile> = emptyList()
    private var shown: List<DocumentFile> = emptyList()
    private var showAll = false

    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private var player: MediaPlayer? = null

    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
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
        val pickButton = findViewById<Button>(R.id.pickButton)
        val allSwitch = findViewById<Switch>(R.id.showAllSwitch)

        pickButton.setOnClickListener { openTree.launch(null) }

        allSwitch.setOnCheckedChangeListener { _, checked ->
            showAll = checked
            applyFilter()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            showFileMenu(shown[position])
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

    private fun refresh() {
        val uri = treeUri ?: return
        val dir = DocumentFile.fromTreeUri(this, uri)
        if (dir == null || !dir.isDirectory) {
            statusText.text = getString(R.string.status_error)
            return
        }
        allFiles = dir.listFiles()
            .filter { it.isFile }
            .sortedBy { it.name?.lowercase(Locale.ROOT) ?: "" }
        applyFilter()
    }

    private fun extensionOf(f: DocumentFile): String =
        (f.name ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun baseNameOf(f: DocumentFile): String =
        (f.name ?: "").substringBeforeLast('.')

    // L'ES-1 ne voit les samples que s'ils sont nommés 00.WAV à 99.WAV (ou .AIF)
    private fun isSample(f: DocumentFile): Boolean =
        extensionOf(f) in setOf("wav", "aif", "aiff")

    private fun sampleNameOk(f: DocumentFile): Boolean {
        val base = baseNameOf(f)
        return base.length == 2 && base.all { it.isDigit() }
    }

    private fun applyFilter() {
        shown = if (showAll) allFiles
        else allFiles.filter { es1Types.containsKey(extensionOf(it)) }

        val warnCount = allFiles.count { isSample(it) && !sampleNameOk(it) }
        val tooMany = allFiles.size > 100

        val sb = StringBuilder(getString(R.string.status_count, shown.size))
        if (warnCount > 0) sb.append("\n").append(getString(R.string.status_badnames, warnCount))
        if (tooMany) sb.append("\n").append(getString(R.string.status_toomany, allFiles.size))
        statusText.text = sb.toString()

        listView.adapter = object : ArrayAdapter<DocumentFile>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, shown
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val f = shown[position]
                var type = es1Types[extensionOf(f)] ?: getString(R.string.type_other)
                if (isSample(f) && !sampleNameOk(f)) {
                    type = "⚠ " + getString(R.string.warn_name) + " — " + type
                }
                v.findViewById<TextView>(android.R.id.text1).text = f.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "$type — ${Formatter.formatShortFileSize(context, f.length())}"
                return v
            }
        }
    }

    private fun showFileMenu(f: DocumentFile) {
        val ext = extensionOf(f)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isSample(f)) {
            actions.add(getString(R.string.action_play) to { playSample(f) })
        }
        if (ext == "txt") {
            actions.add(getString(R.string.action_read) to { showTextFile(f) })
        }
        if (ext == "es1") {
            actions.add(getString(R.string.action_extract) to { extractBank(f) })
            actions.add(getString(R.string.action_analyze) to { analyzeBank(f) })
        }
        actions.add(getString(R.string.action_info) to { showInfo(f) })
        actions.add(getString(R.string.action_rename) to { renameDialog(f) })
        actions.add(getString(R.string.action_delete) to { deleteDialog(f) })

        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .show()
    }

    // Écoute un sample .WAV directement depuis la carte
    private fun playSample(f: DocumentFile) {
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, f.uri)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopPlayer() }
                setOnErrorListener { _, _, _ -> stopPlayer(); toast(R.string.msg_play_error); true }
                prepareAsync()
            }
            Toast.makeText(this, getString(R.string.msg_playing, f.name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            stopPlayer()
            toast(R.string.msg_play_error)
        }
    }

    private fun stopPlayer() {
        player?.release()
        player = null
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }

    // Affiche le contenu de names.txt (liste des noms d'origine des samples)
    private fun showTextFile(f: DocumentFile) {
        val text = try {
            contentResolver.openInputStream(f.uri)?.use { input ->
                input.bufferedReader().readText().take(8000)
            } ?: getString(R.string.msg_read_error)
        } catch (e: Exception) {
            getString(R.string.msg_read_error)
        }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage(if (text.isBlank()) getString(R.string.msg_empty_file) else text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // Extrait les samples d'une banque .es1 grâce au décodeur es12wav embarqué
    private fun extractBank(f: DocumentFile) {
        val progress = AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage(getString(R.string.msg_extracting))
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            var output = ""
            var wavs: List<java.io.File> = emptyList()
            try {
                // 1. Copier la banque depuis la carte vers le stockage privé
                val bankCopy = java.io.File(cacheDir, "bank.ES1")
                contentResolver.openInputStream(f.uri)?.use { input ->
                    bankCopy.outputStream().use { out -> input.copyTo(out) }
                }
                // 2. Dossier de sortie
                val outDir = java.io.File(filesDir, "extracted")
                outDir.deleteRecursively()
                outDir.mkdirs()
                // 3. Lancer le décodeur (empaqueté comme libes12wav.so)
                val exe = java.io.File(applicationInfo.nativeLibraryDir, "libes12wav.so")
                val proc = ProcessBuilder(exe.absolutePath, bankCopy.absolutePath)
                    .directory(outDir)
                    .redirectErrorStream(true)
                    .start()
                output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                bankCopy.delete()
                // 4. Lister les WAV produits (dans le dossier de sortie ou à côté)
                wavs = (outDir.listFiles()?.toList() ?: emptyList())
                    .filter { it.name.lowercase(Locale.ROOT).endsWith(".wav") }
                    .sortedBy { it.name }
            } catch (e: Exception) {
                output += "\n" + (e.message ?: e.toString())
            }
            runOnUiThread {
                progress.dismiss()
                if (wavs.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(f.name)
                        .setMessage(getString(R.string.msg_no_wav) + "\n\n" + output.take(3000))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    showExtractedList(wavs)
                }
            }
        }.start()
    }

    // Liste les samples extraits : taper pour écouter, bouton pour exporter
    private fun showExtractedList(wavs: List<java.io.File>) {
        val names = wavs.map {
            "${it.name}  (${Formatter.formatShortFileSize(this, it.length())})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.extracted_title, wavs.size))
            .setItems(names) { dialog, which ->
                playLocalFile(wavs[which])
                // Rouvrir la liste pour pouvoir enchaîner les écoutes
                dialog.dismiss()
                showExtractedList(wavs)
            }
            .setPositiveButton(R.string.action_export) { _, _ -> exportWavs(wavs) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun playLocalFile(file: java.io.File) {
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

    // Copie les WAV extraits vers le dossier choisi (la carte, par exemple)
    private fun exportWavs(wavs: List<java.io.File>) {
        val uri = treeUri ?: run { toast(R.string.msg_no_folder); return }
        val dir = DocumentFile.fromTreeUri(this, uri) ?: run { toast(R.string.msg_failed); return }
        Thread {
            var ok = 0
            for (w in wavs) {
                try {
                    dir.findFile(w.name)?.delete()
                    val dest = dir.createFile("audio/wav", w.name) ?: continue
                    contentResolver.openOutputStream(dest.uri)?.use { out ->
                        w.inputStream().use { it.copyTo(out) }
                    }
                    ok++
                } catch (e: Exception) { /* fichier suivant */ }
            }
            runOnUiThread {
                Toast.makeText(this, getString(R.string.msg_exported, ok), Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    // Vérifie la signature d'une banque .es1 et explique son contenu
    private fun analyzeBank(f: DocumentFile) {
        val sb = StringBuilder()
        try {
            contentResolver.openInputStream(f.uri)?.use { input ->
                val header = ByteArray(8)
                input.readFully(header)
                val magic = String(header, 0, 4)
                if (magic == "KORG") {
                    sb.append(getString(R.string.bank_valid))
                } else {
                    sb.append(getString(R.string.bank_invalid))
                }
            }
        } catch (e: Exception) {
            sb.append(getString(R.string.msg_read_error))
        }
        sb.append("\n\n")
        sb.append(getString(R.string.bank_size, Formatter.formatFileSize(this, f.length())))
        sb.append("\n\n")
        sb.append(getString(R.string.bank_explain))
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showInfo(f: DocumentFile) {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Date(f.lastModified()))
        val type = es1Types[extensionOf(f)] ?: getString(R.string.type_other)
        val sb = StringBuilder(
            getString(
                R.string.info_body,
                type,
                Formatter.formatFileSize(this, f.length()),
                date
            )
        )
        if (extensionOf(f) == "wav") {
            readWavInfo(f)?.let { (rate, bits, channels) ->
                sb.append("\n\n").append(
                    getString(R.string.info_wav, rate, bits,
                        if (channels == 1) "mono" else "stéréo")
                )
                if (rate != 32000) sb.append("\n").append(getString(R.string.warn_rate))
                if (bits != 8 && bits != 16) sb.append("\n").append(getString(R.string.warn_bits))
            }
        }
        if (isSample(f) && !sampleNameOk(f)) {
            sb.append("\n\n").append(getString(R.string.warn_name_long))
        }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // Lit l'en-tête WAV : (fréquence, bits, canaux) — l'ES-1 exige 32000 Hz, 8/16 bits
    private fun readWavInfo(f: DocumentFile): Triple<Int, Int, Int>? {
        return try {
            contentResolver.openInputStream(f.uri)?.use { input ->
                val header = ByteArray(64)
                val read = input.readFully(header)
                if (read < 36) return null
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null
                // Chercher le chunk "fmt "
                var pos = 12
                while (pos + 8 <= read) {
                    val id = String(header, pos, 4)
                    val size = le32(header, pos + 4)
                    if (id == "fmt ") {
                        val channels = le16(header, pos + 10)
                        val rate = le32(header, pos + 12)
                        val bits = le16(header, pos + 22)
                        return Triple(rate, bits, channels)
                    }
                    pos += 8 + size
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun InputStream.readFully(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        le16(b, o) or (le16(b, o + 2) shl 16)

    private fun renameDialog(f: DocumentFile) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(f.name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && f.renameTo(newName)) {
                    toast(R.string.msg_renamed)
                } else {
                    toast(R.string.msg_failed)
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteDialog(f: DocumentFile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, f.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (f.delete()) toast(R.string.msg_deleted) else toast(R.string.msg_failed)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) =
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
