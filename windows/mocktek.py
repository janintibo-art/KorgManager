"""
Mock'Tek Family Korg — version Windows
Gestion de la carte SmartMedia du Korg Electribe ES-1 mkII.

Fonctions : lister la carte, écouter les samples, extraire les banques .ES1,
nommer les sons (NAMES.TXT), importer et convertir n'importe quel audio
au format ES-1 (32 000 Hz, 16 bits), renommer, supprimer.
"""

import os
import sys
import string
import struct
import shutil
import subprocess
import threading
import tempfile
import wave
import audioop

import tkinter as tk
from tkinter import ttk, filedialog, messagebox, simpledialog

APP_NAME = "Mock'Tek Family Korg"
ES1_RATE = 32000
MAX_FILES = 100

ES1_TYPES = {
    ".es1": "Banque ES-1 (samples + patterns + songs)",
    ".wav": "Sample WAV",
    ".aif": "Sample AIFF",
    ".aiff": "Sample AIFF",
    ".txt": "names.txt (noms des sons)",
}

BG = "#1b1030"
FG = "#f2f2f2"
ACCENT = "#25d7e8"
PANEL = "#2a1a4a"


def resource_path(name):
    """Chemin d'une ressource, y compris quand le .exe est packagé."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


# ---------------------------------------------------------------- audio

def decode_to_pcm(path):
    """Décode un fichier audio en (frames, rate, channels, width) via ffmpeg si besoin."""
    ext = os.path.splitext(path)[1].lower()
    if ext == ".wav":
        try:
            with wave.open(path, "rb") as w:
                return (w.readframes(w.getnframes()), w.getframerate(),
                        w.getnchannels(), w.getsampwidth())
        except Exception:
            pass
    # Autres formats : ffmpeg vers WAV temporaire
    tmp = os.path.join(tempfile.gettempdir(), "mocktek_decode.wav")
    ff = shutil.which("ffmpeg") or resource_path("ffmpeg.exe")
    if not os.path.exists(ff) and not shutil.which("ffmpeg"):
        raise RuntimeError(
            "Ce format nécessite ffmpeg.\n\n"
            "Installez ffmpeg (https://ffmpeg.org) ou convertissez le fichier "
            "en WAV avant de l'importer."
        )
    subprocess.run([ff, "-y", "-i", path, "-f", "wav", tmp],
                   check=True, capture_output=True,
                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    with wave.open(tmp, "rb") as w:
        data = (w.readframes(w.getnframes()), w.getframerate(),
                w.getnchannels(), w.getsampwidth())
    try:
        os.remove(tmp)
    except OSError:
        pass
    return data


def to_es1_wav(path, out_path, normalize=False):
    """Convertit un audio au format ES-1 : 32 000 Hz, 16 bits."""
    frames, rate, channels, width = decode_to_pcm(path)
    if width != 2:
        frames = audioop.lin2lin(frames, width, 2)
        width = 2
    if rate != ES1_RATE:
        frames, _ = audioop.ratecv(frames, 2, channels, rate, ES1_RATE, None)
    if normalize:
        peak = audioop.max(frames, 2)
        if 0 < peak < 31000:
            frames = audioop.mul(frames, 2, 31000.0 / peak)
    with wave.open(out_path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(ES1_RATE)
        w.writeframes(frames)
    duration = len(frames) / (2 * channels * ES1_RATE)
    return duration


def wav_info(path):
    """(rate, bits, channels) d'un WAV, ou None."""
    try:
        with wave.open(path, "rb") as w:
            return w.getframerate(), w.getsampwidth() * 8, w.getnchannels()
    except Exception:
        return None


# ---------------------------------------------------------------- noms

def names_path(folder):
    for n in os.listdir(folder):
        if n.lower() == "names.txt":
            return os.path.join(folder, n)
    return os.path.join(folder, "NAMES.TXT")


def load_names(folder):
    path = names_path(folder)
    out = {}
    if not os.path.exists(path):
        return out
    try:
        with open(path, "r", encoding="latin-1") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                sep = line.find("=") if "=" in line else line.find(" ")
                if sep <= 0:
                    continue
                out[line[:sep].strip().upper()] = line[sep + 1:].strip()
    except Exception:
        pass
    return out


def save_names(folder, names):
    path = names_path(folder)
    with open(path, "w", encoding="latin-1", newline="\r\n") as f:
        for k in sorted(names):
            f.write("%s=%s\n" % (k, names[k]))


# ---------------------------------------------------------------- appli

class App(tk.Tk):

    def __init__(self):
        super().__init__()
        self.title(APP_NAME)
        self.geometry("900x620")
        self.configure(bg=BG)
        try:
            self.iconbitmap(resource_path("icone.ico"))
        except Exception:
            pass

        self.folder = None
        self.names = {}
        self.items = []
        self.tmpdir = tempfile.mkdtemp(prefix="mocktek_")

        self._build_ui()
        self._detect_card()

    # ---------- interface ----------

    def _build_ui(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("Treeview", background=PANEL, fieldbackground=PANEL,
                        foreground=FG, rowheight=26, borderwidth=0)
        style.configure("Treeview.Heading", background=BG, foreground=ACCENT)
        style.map("Treeview", background=[("selected", ACCENT)],
                  foreground=[("selected", BG)])

        header = tk.Frame(self, bg=BG)
        header.pack(fill="x", padx=12, pady=(12, 6))
        tk.Label(header, text="MOCK'TEK FAMILY KORG", bg=BG, fg=ACCENT,
                 font=("Segoe UI", 16, "bold")).pack(side="left")
        tk.Button(header, text="❓ Aide", command=self.show_help,
                  bg=PANEL, fg=FG, relief="flat", padx=10).pack(side="right")

        bar = tk.Frame(self, bg=BG)
        bar.pack(fill="x", padx=12)
        for text, cmd in (("📀 Ouvrir la carte", self.pick_folder),
                          ("➕ Ajouter un son", self.add_sound),
                          ("🔄 Actualiser", self.refresh)):
            tk.Button(bar, text=text, command=cmd, bg=PANEL, fg=FG,
                      relief="flat", padx=14, pady=6).pack(side="left", padx=(0, 8))

        self.status = tk.Label(self, text="Aucune carte ouverte", bg=BG, fg=FG,
                               anchor="w", font=("Segoe UI", 10, "italic"))
        self.status.pack(fill="x", padx=12, pady=(8, 4))

        cols = ("fichier", "nom", "type", "taille")
        self.tree = ttk.Treeview(self, columns=cols, show="headings", selectmode="browse")
        for c, w in zip(cols, (110, 220, 330, 100)):
            self.tree.heading(c, text=c.capitalize())
            self.tree.column(c, width=w, anchor="w")
        self.tree.pack(fill="both", expand=True, padx=12, pady=6)
        self.tree.bind("<Double-1>", lambda e: self.play_selected())

        actions = tk.Frame(self, bg=BG)
        actions.pack(fill="x", padx=12, pady=(0, 12))
        for text, cmd in (("▶ Écouter", self.play_selected),
                          ("🏷 Nommer", self.name_selected),
                          ("📦 Extraire la banque", self.extract_selected),
                          ("🎚 Convertir 32 kHz", lambda: self.convert_selected(False)),
                          ("📈 Normaliser", lambda: self.convert_selected(True)),
                          ("✏ Renommer", self.rename_selected),
                          ("🗑 Supprimer", self.delete_selected)):
            tk.Button(actions, text=text, command=cmd, bg=PANEL, fg=FG,
                      relief="flat", padx=10, pady=5).pack(side="left", padx=(0, 6))

    # ---------- carte ----------

    def _detect_card(self):
        """Cherche un lecteur amovible contenant des fichiers ES-1."""
        for letter in string.ascii_uppercase:
            root = "%s:\\" % letter
            if not os.path.exists(root):
                continue
            try:
                files = os.listdir(root)
            except OSError:
                continue
            if any(os.path.splitext(f)[1].lower() in (".es1", ".wav") for f in files):
                self.folder = root
                self.refresh()
                return

    def pick_folder(self):
        folder = filedialog.askdirectory(title="Choisir la carte SmartMedia (ex : E:\\)")
        if folder:
            self.folder = folder
            self.refresh()

    def refresh(self):
        self.tree.delete(*self.tree.get_children())
        if not self.folder or not os.path.isdir(self.folder):
            self.status.config(text="Aucune carte ouverte")
            return
        self.names = load_names(self.folder)
        entries = []
        for n in sorted(os.listdir(self.folder), key=str.lower):
            full = os.path.join(self.folder, n)
            if os.path.isfile(full):
                entries.append((n, os.path.getsize(full)))
        self.items = entries

        warn = 0
        for name, size in entries:
            ext = os.path.splitext(name)[1].lower()
            base = os.path.splitext(name)[0]
            typ = ES1_TYPES.get(ext, "Autre fichier")
            if ext in (".wav", ".aif", ".aiff") and not (len(base) == 2 and base.isdigit()):
                typ = "⚠ nom non conforme — " + typ
                warn += 1
            friendly = self.names.get(name.upper(), "")
            self.tree.insert("", "end", values=(name, friendly, typ, self._fmt(size)))

        msg = "%s — %d fichier(s)" % (self.folder, len(entries))
        if warn:
            msg += "   ⚠ %d sample(s) mal nommé(s) : l'ES-1 exige 00.WAV à 99.WAV" % warn
        if len(entries) > MAX_FILES:
            msg += "   ⚠ plus de 100 fichiers : l'ES-1 n'en lit que 100"
        self.status.config(text=msg)

    @staticmethod
    def _fmt(size):
        for unit in ("o", "Ko", "Mo"):
            if size < 1024:
                return "%.0f %s" % (size, unit)
            size /= 1024.0
        return "%.1f Go" % size

    def _selected(self):
        sel = self.tree.selection()
        if not sel:
            messagebox.showinfo(APP_NAME, "Sélectionnez d'abord un fichier.")
            return None
        return self.tree.item(sel[0], "values")[0]

    # ---------- actions ----------

    def play_selected(self):
        name = self._selected()
        if not name:
            return
        path = os.path.join(self.folder, name)
        if os.path.splitext(name)[1].lower() not in (".wav", ".aif", ".aiff"):
            messagebox.showinfo(APP_NAME, "Ce fichier n'est pas un sample.")
            return
        self._play_file(path)

    def _play_file(self, path):
        try:
            import winsound
            winsound.PlaySound(path, winsound.SND_FILENAME | winsound.SND_ASYNC)
        except Exception:
            os.startfile(path)

    def name_selected(self):
        name = self._selected()
        if not name:
            return
        key = name.upper()
        value = simpledialog.askstring(
            APP_NAME, "Nom du son %s :" % name,
            initialvalue=self.names.get(key, ""), parent=self)
        if value is None:
            return
        value = value.strip()
        if value:
            self.names[key] = value
        else:
            self.names.pop(key, None)
        try:
            save_names(self.folder, self.names)
            self.refresh()
        except Exception as e:
            messagebox.showerror(APP_NAME, "Écriture impossible : %s" % e)

    def rename_selected(self):
        name = self._selected()
        if not name:
            return
        new = simpledialog.askstring(APP_NAME, "Nouveau nom de fichier :",
                                     initialvalue=name, parent=self)
        if not new or new == name:
            return
        try:
            os.rename(os.path.join(self.folder, name), os.path.join(self.folder, new))
            self.refresh()
        except Exception as e:
            messagebox.showerror(APP_NAME, "Renommage impossible : %s" % e)

    def delete_selected(self):
        name = self._selected()
        if not name:
            return
        if not messagebox.askyesno(APP_NAME, "Supprimer définitivement %s ?" % name):
            return
        try:
            os.remove(os.path.join(self.folder, name))
            self.refresh()
        except Exception as e:
            messagebox.showerror(APP_NAME, "Suppression impossible : %s" % e)

    def convert_selected(self, normalize):
        name = self._selected()
        if not name:
            return
        path = os.path.join(self.folder, name)
        try:
            tmp = os.path.join(self.tmpdir, "conv.wav")
            to_es1_wav(path, tmp, normalize=normalize)
            shutil.copyfile(tmp, path)
            self.refresh()
            messagebox.showinfo(APP_NAME, "%s converti au format ES-1 ✓" % name)
        except Exception as e:
            messagebox.showerror(APP_NAME, "Conversion impossible :\n%s" % e)

    def add_sound(self):
        if not self.folder:
            messagebox.showinfo(APP_NAME, "Ouvrez d'abord la carte.")
            return
        path = filedialog.askopenfilename(
            title="Choisir un son à ajouter",
            filetypes=[("Fichiers audio", "*.wav *.mp3 *.flac *.aiff *.aif *.ogg *.m4a"),
                       ("Tous les fichiers", "*.*")])
        if not path:
            return
        used = {os.path.splitext(n)[0].upper() for n, _ in self.items}
        target = None
        for i in range(100):
            if "%02d" % i not in used:
                target = "%02d.WAV" % i
                break
        if target is None:
            messagebox.showinfo(APP_NAME, "Plus de numéro libre (00 à 99 utilisés).")
            return
        try:
            tmp = os.path.join(self.tmpdir, "import.wav")
            duration = to_es1_wav(path, tmp)
            name = simpledialog.askstring(
                APP_NAME,
                "Converti en 32 kHz / 16 bits (%.1f s).\nNom du fichier sur la carte :" % duration,
                initialvalue=target, parent=self)
            if not name:
                return
            shutil.copyfile(tmp, os.path.join(self.folder, name))
            friendly = simpledialog.askstring(
                APP_NAME, "Nom du son (facultatif) :", parent=self)
            if friendly:
                self.names[name.upper()] = friendly.strip()
                save_names(self.folder, self.names)
            self.refresh()
        except Exception as e:
            messagebox.showerror(APP_NAME, "Import impossible :\n%s" % e)

    # ---------- banques .ES1 ----------

    def extract_selected(self):
        name = self._selected()
        if not name:
            return
        if not name.lower().endswith(".es1"):
            messagebox.showinfo(APP_NAME, "Sélectionnez une banque .ES1.")
            return
        exe = shutil.which("es12wav") or resource_path("es12wav.exe")
        if not os.path.exists(exe):
            messagebox.showerror(
                APP_NAME,
                "Le décodeur es12wav.exe est introuvable.\n"
                "Placez-le à côté de l'application.")
            return
        outdir = os.path.join(self.tmpdir, "extract")
        shutil.rmtree(outdir, ignore_errors=True)
        try:
            res = subprocess.run(
                [exe, os.path.join(self.folder, name), outdir],
                capture_output=True, text=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            wavs = []
            for root, _, files in os.walk(outdir):
                for f in sorted(files):
                    if f.lower().endswith(".wav"):
                        wavs.append(os.path.join(root, f))
            if not wavs:
                messagebox.showerror(APP_NAME,
                                     "Aucun sample extrait.\n\n%s" % (res.stdout or res.stderr))
                return
            SampleWindow(self, wavs)
        except Exception as e:
            messagebox.showerror(APP_NAME, "Extraction impossible :\n%s" % e)

    def show_help(self):
        messagebox.showinfo(APP_NAME, HELP_TEXT)


class SampleWindow(tk.Toplevel):
    """Fenêtre des samples extraits : écouter et nommer, puis exporter."""

    def __init__(self, app, wavs):
        super().__init__(app)
        self.app = app
        self.wavs = wavs
        self.title("%d sample(s) extraits" % len(wavs))
        self.geometry("640x520")
        self.configure(bg=BG)

        tk.Label(self, text="▶ pour écouter, tapez un nom, puis Exporter",
                 bg=BG, fg=ACCENT, font=("Segoe UI", 11, "bold")).pack(pady=8)

        canvas = tk.Canvas(self, bg=BG, highlightthickness=0)
        scroll = ttk.Scrollbar(self, orient="vertical", command=canvas.yview)
        frame = tk.Frame(canvas, bg=BG)
        frame.bind("<Configure>",
                   lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=frame, anchor="nw")
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side="left", fill="both", expand=True, padx=(10, 0))
        scroll.pack(side="right", fill="y")

        self.fields = []
        self.targets = []
        for i, w in enumerate(wavs):
            base = os.path.splitext(os.path.basename(w))[0]
            target = "%s.WAV" % base if len(base) == 2 and base.isdigit() else "%02d.WAV" % i
            self.targets.append(target)

            row = tk.Frame(frame, bg=BG)
            row.pack(fill="x", pady=3)
            tk.Button(row, text="▶", width=3, bg=PANEL, fg=FG, relief="flat",
                      command=lambda p=w: self.app._play_file(p)).pack(side="left")
            tk.Label(row, text=target, bg=BG, fg=FG, width=10,
                     anchor="w").pack(side="left", padx=6)
            entry = tk.Entry(row, bg=PANEL, fg=FG, insertbackground=FG, width=40)
            entry.insert(0, self.app.names.get(target.upper(), ""))
            entry.pack(side="left", fill="x", expand=True, padx=6)
            self.fields.append(entry)

        tk.Button(self, text="💾 Exporter sur la carte", command=self.export,
                  bg=ACCENT, fg=BG, relief="flat", padx=16, pady=8).pack(pady=10)

    def export(self):
        try:
            for i, w in enumerate(self.wavs):
                target = self.targets[i]
                shutil.copyfile(w, os.path.join(self.app.folder, target))
                value = self.fields[i].get().strip()
                if value:
                    self.app.names[target.upper()] = value
                else:
                    self.app.names.pop(target.upper(), None)
            save_names(self.app.folder, self.app.names)
            self.app.refresh()
            messagebox.showinfo(APP_NAME, "%d fichier(s) exportés ✓" % len(self.wavs))
            self.destroy()
        except Exception as e:
            messagebox.showerror(APP_NAME, "Export impossible :\n%s" % e)


HELP_TEXT = """MOCK'TEK FAMILY KORG — version Windows
Gestion de la SmartMedia de l'Electribe ES-1 mkII

1. OUVRIR LA CARTE
Insérez la carte dans le lecteur : l'appli la détecte au lancement.
Sinon, "Ouvrir la carte" et choisissez la lettre du lecteur (E:\\, F:\\...).

2. LES SONS
Sélectionnez un fichier puis :
  ▶ Écouter — 🏷 Nommer — ✏ Renommer — 🗑 Supprimer
  🎚 Convertir 32 kHz : remet un WAV au format ES-1
  📈 Normaliser : remonte le volume d'un sample trop faible

3. BANQUES .ES1
"Extraire la banque" décode tous les samples : une fenêtre s'ouvre pour
les écouter (▶) et les nommer, puis "Exporter" les écrit sur la carte.

4. AJOUTER UN SON
"Ajouter un son" : n'importe quel audio (MP3, WAV, FLAC...) est converti
en 32 000 Hz / 16 bits et copié sur la carte au premier numéro libre.

5. CÔTÉ ES-1
Sample → Card → Load Sample pour charger un WAV, puis All Save.
All Load charge une banque complète.

RÈGLES DE L'ES-1
• Samples nommés 00.WAV à 99.WAV, à la racine de la carte
• 32 000 Hz, 8 ou 16 bits (sinon erreur Er.4)
• 100 fichiers maximum, ~95 s de mémoire en mono
• Formatez toujours la carte dans l'ES-1

Les noms des sons sont enregistrés dans NAMES.TXT sur la carte :
ils suivent la carte et sont partagés avec la version Android.

Pensez à sauvegarder votre carte avant toute modification !
"""


if __name__ == "__main__":
    App().mainloop()
