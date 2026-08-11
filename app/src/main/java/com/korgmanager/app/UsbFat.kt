package com.korgmanager.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.util.Locale

/**
 * Pilote USB "Bulk-Only Transport" + commandes SCSI de base.
 * Permet de lire/écrire les secteurs bruts d'un lecteur de carte USB,
 * même quand Android ne sait pas monter le système de fichiers (FAT12).
 */
class UsbCardReader(private val manager: UsbManager, private val device: UsbDevice) {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEp: UsbEndpoint? = null
    private var outEp: UsbEndpoint? = null
    private var tag = 1
    private var activeLun = 0
    var blockSize = 512
        private set
    var blockCount = 0L
        private set

    fun open() {
        var intf: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val cand = device.getInterface(i)
            if (cand.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                intf = cand
                break
            }
        }
        if (intf == null) throw IOException("Pas d'interface de stockage USB")

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
        if (epIn == null || epOut == null) throw IOException("Endpoints USB introuvables")

        val conn = manager.openDevice(device) ?: throw IOException("Ouverture du périphérique refusée")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("Interface USB occupée")
        }
        connection = conn
        usbInterface = intf
        inEp = epIn
        outEp = epOut

        // Les lecteurs multi-cartes exposent plusieurs emplacements (LUN) :
        // on les teste tous jusqu'à trouver celui qui contient la carte.
        val maxLun = getMaxLun()
        var found = false
        outer@ for (lun in 0..maxLun) {
            activeLun = lun
            for (attempt in 0 until 6) {
                if (testUnitReady()) {
                    try {
                        readCapacity()
                        if (blockCount > 0) { found = true; break@outer }
                    } catch (e: Exception) { /* emplacement vide */ }
                }
                requestSense()
                Thread.sleep(120)
            }
        }
        if (!found) {
            throw IOException("Aucune carte trouvée dans le lecteur (${maxLun + 1} emplacement(s) testé(s))")
        }
    }

    private fun getMaxLun(): Int {
        val conn = connection ?: return 0
        val intf = usbInterface ?: return 0
        val buf = ByteArray(1)
        val n = conn.controlTransfer(0xA1, 0xFE, 0, intf.id, buf, 1, 2000)
        return if (n == 1) (buf[0].toInt() and 0xFF).coerceIn(0, 15) else 0
    }

    private fun clearStall(ep: UsbEndpoint?) {
        if (ep == null) return
        try {
            connection?.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 2000)
        } catch (e: Exception) { /* ignore */ }
    }

    fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) { /* ignore */ }
        connection = null
    }

    // ---------- Transport Bulk-Only ----------

    private fun bulkOut(data: ByteArray, length: Int) {
        val conn = connection ?: throw IOException("USB fermé")
        var sent = 0
        var retried = false
        while (sent < length) {
            val n = conn.bulkTransfer(outEp, data.copyOfRange(sent, length), length - sent, 5000)
            if (n < 0) {
                if (!retried) { retried = true; clearStall(outEp); continue }
                throw IOException("Erreur d'écriture USB")
            }
            sent += n
        }
    }

    private fun bulkIn(length: Int): ByteArray {
        val conn = connection ?: throw IOException("USB fermé")
        val out = ByteArray(length)
        var got = 0
        var retried = false
        while (got < length) {
            val buf = ByteArray(length - got)
            val n = conn.bulkTransfer(inEp, buf, buf.size, 5000)
            if (n < 0) {
                if (!retried) { retried = true; clearStall(inEp); continue }
                throw IOException("Erreur de lecture USB")
            }
            System.arraycopy(buf, 0, out, got, n)
            got += n
        }
        return out
    }

    private fun scsi(cb: ByteArray, dataOut: ByteArray?, dataInLen: Int): ByteArray {
        val myTag = tag++
        val dataLen = dataOut?.size ?: dataInLen
        val cbw = ByteArray(31)
        putLe32(cbw, 0, 0x43425355) // "USBC"
        putLe32(cbw, 4, myTag)
        putLe32(cbw, 8, dataLen)
        cbw[12] = if (dataOut == null && dataInLen > 0) 0x80.toByte() else 0x00
        cbw[13] = activeLun.toByte()
        cbw[14] = cb.size.toByte()
        System.arraycopy(cb, 0, cbw, 15, cb.size)
        bulkOut(cbw, 31)

        var result = ByteArray(0)
        if (dataOut != null && dataOut.isNotEmpty()) bulkOut(dataOut, dataOut.size)
        else if (dataInLen > 0) result = bulkIn(dataInLen)

        val csw = bulkIn(13)
        if (le32(csw, 0) != 0x53425355) throw IOException("Réponse USB invalide")
        if (csw[12].toInt() != 0) throw IOException("Commande refusée par le lecteur (statut ${csw[12]})")
        return result
    }

    // ---------- Commandes SCSI ----------

    private fun testUnitReady(): Boolean {
        return try {
            scsi(ByteArray(6), null, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun requestSense() {
        try {
            val cb = ByteArray(6)
            cb[0] = 0x03
            cb[4] = 18
            scsi(cb, null, 18)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun readCapacity() {
        val data = scsi(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, 8)
        blockCount = (be32(data, 0).toLong() and 0xFFFFFFFFL) + 1
        blockSize = be32(data, 4)
        if (blockSize <= 0 || blockSize > 4096) blockSize = 512
    }

    /** Lit `count` secteurs à partir du secteur `lba`. */
    fun read(lba: Long, count: Int): ByteArray {
        val out = ByteArray(count * blockSize)
        var done = 0
        while (done < count) {
            val chunk = minOf(count - done, 64)
            val cb = ByteArray(10)
            cb[0] = 0x28
            putBe32(cb, 2, (lba + done).toInt())
            putBe16(cb, 7, chunk)
            val data = scsi(cb, null, chunk * blockSize)
            System.arraycopy(data, 0, out, done * blockSize, data.size)
            done += chunk
        }
        return out
    }

    /** Écrit des secteurs à partir du secteur `lba`. */
    fun write(lba: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "Taille non alignée sur les secteurs" }
        val count = data.size / blockSize
        var done = 0
        while (done < count) {
            val chunk = minOf(count - done, 64)
            val cb = ByteArray(10)
            cb[0] = 0x2A
            putBe32(cb, 2, (lba + done).toInt())
            putBe16(cb, 7, chunk)
            val part = data.copyOfRange(done * blockSize, (done + chunk) * blockSize)
            scsi(cb, part, 0)
            done += chunk
        }
    }

    companion object {
        fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
        fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun be32(b: ByteArray, o: Int) = (be16(b, o) shl 16) or be16(b, o + 2)
        fun putLe16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun putLe32(b: ByteArray, o: Int, v: Int) { putLe16(b, o, v and 0xFFFF); putLe16(b, o + 2, v ushr 16) }
        fun putBe16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
        fun putBe32(b: ByteArray, o: Int, v: Int) { putBe16(b, o, v ushr 16); putBe16(b, o + 2, v and 0xFFFF) }
    }
}

/** Une entrée (fichier) du répertoire racine FAT. */
data class FatEntry(
    val name: String,
    val size: Long,
    val startCluster: Int,
    val entryIndex: Int
)

/**
 * Système de fichiers FAT12/FAT16 minimal (lecture, écriture, renommage,
 * suppression dans le répertoire racine) — le format des cartes SmartMedia
 * formatées par le Korg ES-1.
 */
class FatFs(private val dev: UsbCardReader) {

    private var partStart = 0L
    private var bps = 512
    private var spc = 1
    private var reservedSectors = 1
    private var numFats = 2
    private var rootEntries = 512
    private var sectorsPerFat = 0
    private var fatStart = 0L
    private var rootStart = 0L
    private var rootSectors = 0
    private var dataStart = 0L
    private var clusterCount = 0
    private var isFat12 = true
    private lateinit var fat: ByteArray

    val typeName: String get() = if (isFat12) "FAT12" else "FAT16"

    init {
        var boot = dev.read(0, 1)
        if (!looksLikeFatBoot(boot)) {
            // Table de partitions MBR ?
            if ((boot[510].toInt() and 0xFF) == 0x55 && (boot[511].toInt() and 0xFF) == 0xAA) {
                val start = UsbCardReader.le32(boot, 0x1BE + 8).toLong() and 0xFFFFFFFFL
                if (start in 1 until dev.blockCount) {
                    partStart = start
                    boot = dev.read(partStart, 1)
                }
            }
        }
        if (!looksLikeFatBoot(boot)) throw IOException("Format de carte non reconnu (pas de FAT12/16)")

        bps = UsbCardReader.le16(boot, 11)
        spc = boot[13].toInt() and 0xFF
        reservedSectors = UsbCardReader.le16(boot, 14)
        numFats = boot[16].toInt() and 0xFF
        rootEntries = UsbCardReader.le16(boot, 17)
        var totalSectors = UsbCardReader.le16(boot, 19)
        if (totalSectors == 0) totalSectors = UsbCardReader.le32(boot, 32)
        sectorsPerFat = UsbCardReader.le16(boot, 22)

        fatStart = partStart + reservedSectors
        rootSectors = (rootEntries * 32 + bps - 1) / bps
        rootStart = fatStart + numFats.toLong() * sectorsPerFat
        dataStart = rootStart + rootSectors
        val dataSectors = totalSectors - (reservedSectors + numFats * sectorsPerFat + rootSectors)
        clusterCount = dataSectors / spc
        isFat12 = clusterCount < 4085

        fat = dev.read(fatStart, sectorsPerFat)
    }

    private fun looksLikeFatBoot(b: ByteArray): Boolean {
        if ((b[510].toInt() and 0xFF) != 0x55 || (b[511].toInt() and 0xFF) != 0xAA) return false
        val bytesPerSector = UsbCardReader.le16(b, 11)
        val sectorsPerCluster = b[13].toInt() and 0xFF
        if (bytesPerSector != dev.blockSize) return false
        if (sectorsPerCluster == 0 || (sectorsPerCluster and (sectorsPerCluster - 1)) != 0) return false
        val jmp = b[0].toInt() and 0xFF
        return jmp == 0xEB || jmp == 0xE9
    }

    // ---------- Table FAT ----------

    private fun fatGet(cluster: Int): Int {
        return if (isFat12) {
            val off = cluster + cluster / 2
            val v = UsbCardReader.le16(fat, off)
            if (cluster % 2 == 1) v ushr 4 else v and 0xFFF
        } else {
            UsbCardReader.le16(fat, cluster * 2)
        }
    }

    private fun fatSet(cluster: Int, value: Int) {
        if (isFat12) {
            val off = cluster + cluster / 2
            val cur = UsbCardReader.le16(fat, off)
            val nv = if (cluster % 2 == 1) (cur and 0x000F) or (value shl 4)
                     else (cur and 0xF000) or (value and 0xFFF)
            UsbCardReader.putLe16(fat, off, nv)
        } else {
            UsbCardReader.putLe16(fat, cluster * 2, value)
        }
    }

    private fun isEoc(v: Int) = if (isFat12) v >= 0xFF8 else v >= 0xFFF8
    private val eocValue: Int get() = if (isFat12) 0xFFF else 0xFFFF

    private fun flushFat() {
        for (i in 0 until numFats) {
            dev.write(fatStart + i.toLong() * sectorsPerFat, fat)
        }
    }

    // ---------- Répertoire racine ----------

    fun list(): List<FatEntry> {
        val root = dev.read(rootStart, rootSectors)
        val out = mutableListOf<FatEntry>()
        for (i in 0 until rootEntries) {
            val o = i * 32
            val first = root[o].toInt() and 0xFF
            if (first == 0x00) break
            if (first == 0xE5) continue
            val attr = root[o + 11].toInt() and 0xFF
            if (attr and 0x08 != 0 || attr == 0x0F) continue // label de volume / nom long
            if (attr and 0x10 != 0) continue // sous-dossier (l'ES-1 n'en utilise pas)
            val base = String(root, o, 8, Charsets.US_ASCII).trim()
            val ext = String(root, o + 8, 3, Charsets.US_ASCII).trim()
            val name = if (ext.isEmpty()) base else "$base.$ext"
            val cluster = UsbCardReader.le16(root, o + 26)
            val size = UsbCardReader.le32(root, o + 28).toLong() and 0xFFFFFFFFL
            out.add(FatEntry(name, size, cluster, i))
        }
        return out.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun writeDirEntry(index: Int, entryBytes: ByteArray) {
        val sector = rootStart + (index * 32) / bps
        val offInSector = (index * 32) % bps
        val sec = dev.read(sector, 1)
        System.arraycopy(entryBytes, 0, sec, offInSector, 32)
        dev.write(sector, sec)
    }

    private fun readDirEntry(index: Int): ByteArray {
        val sector = rootStart + (index * 32) / bps
        val offInSector = (index * 32) % bps
        val sec = dev.read(sector, 1)
        return sec.copyOfRange(offInSector, offInSector + 32)
    }

    // ---------- Opérations sur fichiers ----------

    fun readFile(e: FatEntry): ByteArray {
        if (e.size == 0L || e.startCluster < 2) return ByteArray(0)
        val clusterBytes = spc * bps
        val out = ByteArray(e.size.toInt())
        var cluster = e.startCluster
        var written = 0
        var guard = 0
        while (written < e.size && guard++ <= clusterCount + 2) {
            val sector = dataStart + (cluster - 2).toLong() * spc
            val data = dev.read(sector, spc)
            val n = minOf(clusterBytes, (e.size - written).toInt())
            System.arraycopy(data, 0, out, written, n)
            written += n
            val next = fatGet(cluster)
            if (isEoc(next)) break
            cluster = next
        }
        return out
    }

    fun delete(e: FatEntry) {
        // Libérer la chaîne de clusters
        var cluster = e.startCluster
        var guard = 0
        while (cluster >= 2 && guard++ <= clusterCount + 2) {
            val next = fatGet(cluster)
            fatSet(cluster, 0)
            if (isEoc(next)) break
            cluster = next
        }
        flushFat()
        // Marquer l'entrée comme supprimée
        val entry = readDirEntry(e.entryIndex)
        entry[0] = 0xE5.toByte()
        writeDirEntry(e.entryIndex, entry)
    }

    fun rename(e: FatEntry, newName: String) {
        val name83 = to83(newName) ?: throw IOException("Nom invalide : 8 caractères max + extension de 3 (ex : 07.WAV)")
        val entry = readDirEntry(e.entryIndex)
        System.arraycopy(name83, 0, entry, 0, 11)
        writeDirEntry(e.entryIndex, entry)
    }

    fun writeFile(name: String, data: ByteArray) {
        val name83 = to83(name) ?: throw IOException("Nom invalide : 8 caractères max + extension de 3")
        // Remplacer un fichier existant du même nom
        list().firstOrNull { it.name.equals(fmt83(name83), ignoreCase = true) }?.let { delete(it) }

        val clusterBytes = spc * bps
        val needed = if (data.isEmpty()) 0 else (data.size + clusterBytes - 1) / clusterBytes

        // Trouver des clusters libres
        val free = mutableListOf<Int>()
        var c = 2
        while (free.size < needed && c < clusterCount + 2) {
            if (fatGet(c) == 0) free.add(c)
            c++
        }
        if (free.size < needed) throw IOException("Espace insuffisant sur la carte")

        // Écrire les données et chaîner les clusters
        for (i in free.indices) {
            val cluster = free[i]
            val start = i * clusterBytes
            val chunk = ByteArray(clusterBytes)
            val n = minOf(clusterBytes, data.size - start)
            System.arraycopy(data, start, chunk, 0, n)
            dev.write(dataStart + (cluster - 2).toLong() * spc, chunk)
            fatSet(cluster, if (i == free.size - 1) eocValue else free[i + 1])
        }
        flushFat()

        // Créer l'entrée de répertoire
        val root = dev.read(rootStart, rootSectors)
        var slot = -1
        for (i in 0 until rootEntries) {
            val first = root[i * 32].toInt() and 0xFF
            if (first == 0x00 || first == 0xE5) { slot = i; break }
        }
        if (slot < 0) throw IOException("Répertoire racine plein (100 fichiers max sur l'ES-1)")

        val entry = ByteArray(32)
        System.arraycopy(name83, 0, entry, 0, 11)
        entry[11] = 0x20 // archive
        val date = ((2026 - 1980) shl 9) or (8 shl 5) or 11
        UsbCardReader.putLe16(entry, 24, date)
        UsbCardReader.putLe16(entry, 26, if (needed == 0) 0 else free[0])
        UsbCardReader.putLe32(entry, 28, data.size)
        writeDirEntry(slot, entry)
    }

    fun freeSpace(): Long {
        var freeClusters = 0
        for (c in 2 until clusterCount + 2) if (fatGet(c) == 0) freeClusters++
        return freeClusters.toLong() * spc * bps
    }

    // ---------- Noms 8.3 ----------

    private fun to83(name: String): ByteArray? {
        val up = name.trim().uppercase(Locale.ROOT)
        if (up.isEmpty() || up.contains(' ')) return null
        val dot = up.lastIndexOf('.')
        val base = if (dot >= 0) up.substring(0, dot) else up
        val ext = if (dot >= 0) up.substring(dot + 1) else ""
        if (base.isEmpty() || base.length > 8 || ext.length > 3) return null
        val ok = { s: String -> s.all { it.isLetterOrDigit() || it in "_-~!#$%&@" } }
        if (!ok(base) || !ok(ext)) return null
        val out = ByteArray(11) { ' '.code.toByte() }
        for (i in base.indices) out[i] = base[i].code.toByte()
        for (i in ext.indices) out[8 + i] = ext[i].code.toByte()
        return out
    }

    private fun fmt83(b: ByteArray): String {
        val base = String(b, 0, 8, Charsets.US_ASCII).trim()
        val ext = String(b, 8, 3, Charsets.US_ASCII).trim()
        return if (ext.isEmpty()) base else "$base.$ext"
    }
}
