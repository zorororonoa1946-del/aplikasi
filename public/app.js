import {
  CHUNK_BYTES,
  ID,
  encoder,
  base64,
  unbase64,
  encrypt,
  decrypt,
  validate,
  openChunk,
  aad,
} from "./format.js";
const $ = (id) => document.getElementById(id);
let password = "",
  records = [],
  generation = 0,
  lastActive = Date.now(),
  busy = false,
  history = [],
  folderPath = "",
  abort,
  pendingFiles = [];
const db = await new Promise((resolve, reject) => {
  const r = indexedDB.open("nusa-vault", 2);
  r.onupgradeneeded = () => {
    for (const name of ["records", "chunks"])
      if (!r.result.objectStoreNames.contains(name))
        r.result.createObjectStore(name);
  };
  r.onsuccess = () => resolve(r.result);
  r.onerror = () => reject(r.error);
});
function tx(name, mode, callback) {
  return new Promise((resolve, reject) => {
    const t = db.transaction(name, mode),
      request = callback(t.objectStore(name));
    t.oncomplete = () => resolve(request?.result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}
const get = (id) => tx("records", "readonly", (s) => s.get(id)),
  put = (id, value) => tx("records", "readwrite", (s) => s.put(value, id));
const chunk = (id, i) => tx("chunks", "readonly", (s) => s.get(`${id}:${i}`));
const putChunk = (id, i, value) =>
  tx("chunks", "readwrite", (s) => s.put(value, `${id}:${i}`));
async function clearChunks(id) {
  await tx("chunks", "readwrite", (s) => {
    const r = s.openCursor(IDBKeyRange.bound(`${id}:`, `${id}:\uffff`));
    r.onsuccess = () => {
      const c = r.result;
      if (c) {
        c.delete();
        c.continue();
      }
    };
    return r;
  });
}
const size = (n) =>
  n < 1024
    ? `${n} B`
    : n < 1048576
      ? `${(n / 1024).toFixed(1)} KB`
      : n < 1073741824
        ? `${(n / 1048576).toFixed(1)} MB`
        : `${(n / 1073741824).toFixed(2)} GB`;
const status = (text) => ($("status").textContent = text);
function renderHistory() {
  $("history").replaceChildren();
  for (const entry of history) {
    const li = document.createElement("li");
    li.textContent = `${new Date(entry.date).toLocaleString("id-ID")} · ${entry.message}`;
    $("history").append(li);
  }
}
function log(message) {
  history.unshift({ date: new Date().toISOString(), message });
  history.length = Math.min(history.length, 500);
  renderHistory();
}
const safeMetadata = (value) => {
  const copy = { ...value };
  delete copy.data;
  delete copy.key;
  delete copy.parts;
  return copy;
};
function lock() {
  generation++;
  abort?.abort();
  password = "";
  records = [];
  history = [];
  folderPath = "";
  for (const id of ["token", "password", "confirm-password", "search"])
    if ($(id)) $(id).value = "";
  $("files").replaceChildren();
  $("history").replaceChildren();
  status("");
  $("file-count").textContent = "0 file";
  $("total-size").textContent = "0 B";
  $("vault").hidden = true;
  $("company").hidden = false;
  $("unlock-dialog").close();
}
function controls() {
  for (const b of $("vault").querySelectorAll("button,input[type=file]"))
    if (b.id !== "lock") b.disabled = busy || b.dataset.unreadable === "true";
}
function active(session) {
  if (session !== generation || !password)
    throw new DOMException("Vault terkunci", "AbortError");
}
async function run(task) {
  if (busy || !password) return;
  busy = true;
  controls();
  const session = generation,
    pass = password;
  abort = new AbortController();
  try {
    await task(session, pass);
  } catch (error) {
    if (session === generation) status(`Gagal: ${error.message}`);
  } finally {
    if (session === generation && password)
      try {
        const [envelope, catalog] = await Promise.all([
          encrypt({ events: history }, pass),
          encrypt(
            { files: records.map((r) => ({ id: r.id, value: r.value })) },
            pass,
          ),
        ]);
        if (session === generation) {
          await put("history", envelope);
          await put("catalog", catalog);
        }
      } catch {
        status("Riwayat belum tersimpan; periksa kapasitas browser.");
      }
    busy = false;
    controls();
    if (pendingFiles.length && password) startPending();
  }
}
async function api(path = "", options = {}, binary = false) {
  if (
    location.protocol !== "https:" &&
    !["localhost", "127.0.0.1", "[::1]"].includes(location.hostname)
  )
    throw Error("HTTPS diperlukan");
  for (let attempt = 0; ; attempt++) {
    const response = await fetch(`/api${path}`, {
      ...options,
      signal: abort?.signal,
      headers: {
        Authorization: `Bearer ${$("token").value}`,
        "Content-Type": binary
          ? "application/octet-stream"
          : "application/json",
        ...options.headers,
      },
    });
    if ([429, 500, 502, 503, 504].includes(response.status) && attempt < 2) {
      await new Promise((r) => setTimeout(r, 500 * (attempt + 1)));
      continue;
    }
    if (response.status === 409) return { conflict: true };
    if (response.status === 404 && options.method === "HEAD") return null;
    if (!response.ok) {
      let message = `Relay HTTP ${response.status}`;
      try {
        message = (await response.json()).error || message;
      } catch {}
      throw Error(message);
    }
    if (options.method === "HEAD")
      return response.headers.get("X-Content-SHA256");
    return binary
      ? new Uint8Array(await response.arrayBuffer())
      : response.json();
  }
}
async function valueOf(record, pass) {
  return validate(await decrypt(record.envelope, pass), record.id);
}
function render() {
  $("file-count").textContent = `${records.length} file`;
  $("total-size").textContent = size(
    records.reduce((n, r) => n + (r.value.size || 0), 0),
  );
  $("files").replaceChildren();
  const query = $("search").value.toLowerCase();
  if (!query) {
    const path = document.createElement("p");
    path.textContent = `/${folderPath}`;
    $("files").append(path);
    if (folderPath) {
      const up = document.createElement("button");
      up.textContent = "↑ Folder induk";
      up.onclick = () => {
        const current = folderPath.slice(0, -1);
        folderPath = current.includes("/")
          ? current.slice(0, current.lastIndexOf("/") + 1)
          : "";
        render();
      };
      $("files").append(up);
    }
    const folders = new Set();
    for (const r of records)
      if (r.value.name.startsWith(folderPath)) {
        const tail = r.value.name.slice(folderPath.length);
        if (tail.includes("/")) folders.add(tail.split("/")[0]);
      }
    for (const name of [...folders].sort()) {
      const b = document.createElement("button");
      b.textContent = `▸ ${name}`;
      b.onclick = () => {
        folderPath += name + "/";
        render();
      };
      $("files").append(b);
    }
  }
  const visible = records.filter((r) =>
    query
      ? r.value.name.toLowerCase().includes(query)
      : r.value.name.startsWith(folderPath) &&
        !r.value.name.slice(folderPath.length).includes("/"),
  );
  for (const record of visible) {
    const row = document.createElement("article");
    row.className = "file";
    const info = document.createElement("div"),
      actions = document.createElement("div"),
      title = document.createElement("h3"),
      meta = document.createElement("small");
    title.textContent = record.value.name;
    meta.textContent = record.value.unreadable
      ? "Data lokal tidak dapat dibuka; coba Pulihkan dari relay"
      : `${size(record.value.size)} · ${new Date(record.value.date).toLocaleDateString("id-ID")} · Terenkripsi`;
    info.append(title, meta);
    const download = document.createElement("button");
    download.textContent = "Unduh";
    download.dataset.unreadable = String(!!record.value.unreadable);
    download.disabled = !!record.value.unreadable;
    download.onclick = () =>
      run((session, pass) => downloadFile(record, session, pass));
    const remove = document.createElement("button");
    remove.textContent = "Hapus";
    remove.onclick = () =>
      run(async (session) => {
        if (
          !confirm(
            "Hapus salinan lokal? Salinan relay hanya ada jika backup sebelumnya berhasil. Pastikan pemulihan sudah diuji.",
          )
        )
          return;
        active(session);
        await tx("records", "readwrite", (s) => s.delete(record.id));
        await clearChunks(record.id);
        active(session);
        records = records.filter((r) => r.id !== record.id);
        render();
        log(`Dihapus lokal: ${record.value.name}`);
      });
    actions.append(download, remove);
    row.append(info, actions);
    $("files").append(row);
  }
  if (!visible.length) {
    const p = document.createElement("p");
    p.className = "empty";
    p.textContent = records.length
      ? "Pilih folder atau cari nama file."
      : "Vault masih kosong. Tambahkan file atau folder pilihan Anda.";
    $("files").append(p);
  }
  controls();
}
async function downloadFile(record, session, pass) {
  let writer; // Obtain a user-approved output stream while user activation is still live.
  if (record.value.size > 64 * 1024 * 1024) {
    if (!window.showSaveFilePicker)
      throw Error(
        "Untuk file besar gunakan Chrome/Edge dengan Simpan file, atau helper restore laptop pada paket instalasi.",
      );
    writer = await (
      await showSaveFilePicker({
        suggestedName: record.value.name.split("/").pop(),
      })
    ).createWritable();
  }
  const blobs = [];
  try {
    const value = await valueOf(record, pass);
    active(session);
    if (value.format === 2) {
      for (let i = 0; i < value.parts.length; i++) {
        active(session);
        const stored = await chunk(record.id, i);
        if (!stored)
          throw Error("Chunk lokal belum tersedia. Pulihkan dari relay.");
        const plain = await openChunk(value, record.id, i, stored);
        if (writer) await writer.write(plain);
        else blobs.push(plain);
      }
    } else {
      const plain = unbase64(value.data);
      if (writer) await writer.write(plain);
      else blobs.push(plain);
    }
    active(session);
    if (writer) await writer.close();
    else {
      const url = URL.createObjectURL(
        new Blob(blobs, { type: "application/octet-stream" }),
      );
      const a = document.createElement("a");
      a.href = url;
      a.download = value.name.split("/").pop();
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    log(`Diunduh: ${value.name}`);
  } catch (error) {
    if (writer) await writer.abort().catch(() => {});
    throw error;
  }
}
async function importFile(file, session, pass) {
  const id = crypto.randomUUID();
  let committed = false;
  try {
    const raw = crypto.getRandomValues(new Uint8Array(32)),
      key = await crypto.subtle.importKey("raw", raw, "AES-GCM", false, [
        "encrypt",
      ]),
      parts = [];
    for (let offset = 0; offset < file.size; offset += CHUNK_BYTES) {
      active(session);
      const bytes = new Uint8Array(
          await file.slice(offset, offset + CHUNK_BYTES).arrayBuffer(),
        ),
        iv = crypto.getRandomValues(new Uint8Array(12)),
        index = parts.length;
      const encrypted = new Uint8Array(
        await crypto.subtle.encrypt(
          { name: "AES-GCM", iv, additionalData: aad(id, index) },
          key,
          bytes,
        ),
      );
      active(session);
      await putChunk(id, index, encrypted);
      parts.push({
        iv: base64(iv),
        size: bytes.length,
        sha256: base64(
          new Uint8Array(await crypto.subtle.digest("SHA-256", encrypted)),
        ),
      });
      status(
        `Mengenkripsi ${file.name}: ${Math.min(file.size, offset + CHUNK_BYTES)} / ${file.size} byte`,
      );
    }
    const value = {
        format: 2,
        fileId: id,
        name: file.webkitRelativePath || file.name,
        size: file.size,
        date: new Date().toISOString(),
        chunkSize: CHUNK_BYTES,
        key: base64(raw),
        parts,
      },
      envelope = await encrypt(value, pass);
    raw.fill(0);
    active(session);
    await put(id, envelope);
    committed = true;
    active(session);
    records.push({ id, value: safeMetadata(value), envelope });
    log(`Diimpor: ${value.name}`);
    render();
  } finally {
    if (!committed) await clearChunks(id);
  }
}
let taps = 0,
  tapTime = 0;
$("brand").onclick = async () => {
  if (Date.now() - tapTime > 3000) taps = 0;
  tapTime = Date.now();
  if (++taps < 7) return;
  taps = 0;
  if (!crypto.subtle) {
    alert("Gunakan HTTPS atau localhost.");
    return;
  }
  if ($("unlock-dialog").open) return;
  const exists = await get("check");
  $("unlock-title").textContent = exists ? "Buka vault" : "Buat vault pribadi";
  $("unlock-help").textContent = exists
    ? "Masukkan kata sandi vault Anda."
    : "Gunakan kata sandi yang sama dengan Android untuk pemulihan. Kata sandi tidak dapat direset.";
  $("confirm-label").hidden = !!exists;
  $("confirm-password").required = !exists;
  $("confirm-password").value = "";
  $("password").value = "";
  $("unlock-error").textContent = "";
  $("unlock-dialog").showModal();
  $("password").focus();
};
$("cancel").onclick = () => {
  generation++;
  $("password").value = "";
  $("confirm-password").value = "";
  $("unlock-dialog").close();
};
$("unlock-dialog").addEventListener("cancel", () => {
  generation++;
  $("password").value = "";
  $("confirm-password").value = "";
});
$("unlock-form").onsubmit = async (event) => {
  event.preventDefault();
  const pass = $("password").value,
    submit = event.submitter,
    attempt = generation;
  const live = () =>
    attempt === generation && !document.hidden && $("unlock-dialog").open;
  submit.disabled = true;
  try {
    const check = await get("check");
    if (check) {
      if ((await decrypt(check, pass)).check !== "nusa-vault")
        throw Error("Kata sandi salah");
    } else {
      if (pass !== $("confirm-password").value)
        throw Error("Konfirmasi kata sandi tidak sama");
      const encrypted = await encrypt({ check: "nusa-vault" }, pass);
      if (!live()) return;
      await put("check", encrypted);
    }
    const ids = (await tx("records", "readonly", (s) => s.getAllKeys())).filter(
        (id) => ID.test(id),
      ),
      loaded = [];
    let catalog;
    try {
      const saved = await get("catalog");
      if (saved) {
        const entries = (await decrypt(saved, pass)).files;
        if (
          Array.isArray(entries) &&
          entries.length === ids.length &&
          new Set(entries.map((r) => r.id)).size === ids.length &&
          entries.every(
            (r) =>
              ids.includes(r.id) && r.value && typeof r.value.name === "string",
          )
        )
          catalog = new Map(entries.map((r) => [r.id, r.value]));
      }
    } catch {}
    for (const id of ids) {
      const envelope = await get(id);
      let value = catalog?.get(id);
      if (!value)
        try {
          value = safeMetadata(validate(await decrypt(envelope, pass), id));
        } catch {
          value = {
            name: `File lokal tidak dapat dibuka (${id.slice(0, 8)})`,
            size: 0,
            unreadable: true,
          };
        }
      if (!live()) return;
      loaded.push({ id, envelope, value });
    }
    let events = [];
    const saved = await get("history");
    if (saved)
      try {
        events = (await decrypt(saved, pass)).events || [];
      } catch {}
    if (!live()) return;
    password = pass;
    generation++;
    records = loaded;
    history = events.slice(0, 500);
    lastActive = Date.now();
    $("password").value = "";
    $("confirm-password").value = "";
    $("unlock-dialog").close();
    $("company").hidden = true;
    $("vault").hidden = false;
    status("Vault terbuka. Kunci otomatis setelah 3 menit tidak aktif.");
    render();
    renderHistory();
    if (pendingFiles.length) startPending();
  } catch (error) {
    if (live())
      $("unlock-error").textContent =
        error.message === "Konfirmasi kata sandi tidak sama"
          ? error.message
          : "Vault tidak dapat dibuka. Periksa kata sandi atau data lokal.";
  } finally {
    submit.disabled = false;
  }
};
$("lock").onclick = lock;
$("search").oninput = render;
for (const event of ["pointerdown", "keydown", "input"])
  document.addEventListener(event, () => (lastActive = Date.now()));
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    if (password) lock();
    else if ($("unlock-dialog").open) {
      generation++;
      $("password").value = "";
      $("confirm-password").value = "";
      $("unlock-dialog").close();
    }
  }
});
setInterval(() => {
  if (password && Date.now() - lastActive > 180000) lock();
}, 1000);
function startPending() {
  if (busy || !password) return;
  const files = pendingFiles;
  pendingFiles = [];
  run(async (session, pass) => {
    let failed = 0;
    for (const file of files)
      try {
        active(session);
        await importFile(file, session, pass);
      } catch (error) {
        if (error.name === "AbortError") throw error;
        failed++;
        log(`Impor gagal: ${file.name} — ${error.message}`);
      }
    status(
      `${files.length - failed} file diimpor, ${failed} gagal. Salinan sumber tetap utuh.`,
    );
  });
}
function selected(event) {
  pendingFiles.push(...event.target.files);
  event.target.value = "";
  startPending();
}
$("import").onchange = selected;
$("import-folder").onchange = selected;
$("backup").onclick = () =>
  run(async (session, pass) => {
    if (!records.length) return status("Tambahkan file terlebih dahulu.");
    let count = 0,
      failed = 0;
    await api("/files");
    for (const record of records) {
      try {
        active(session);
        const value = await valueOf(record, pass);
        if (value.format === 2)
          for (let i = 0; i < value.parts.length; i++) {
            active(session);
            const part = value.parts[i],
              path = `/chunks/${record.id}/${i}`,
              existing = await api(path, { method: "HEAD" });
            if (existing) {
              if (existing !== part.sha256)
                throw Error("Konflik chunk; salinan lama dipertahankan");
              continue;
            }
            const data = await chunk(record.id, i);
            if (!data) throw Error("Chunk lokal hilang");
            await openChunk(value, record.id, i, data);
            const result = await api(path, { method: "PUT", body: data }, true);
            if (
              result.conflict &&
              (await api(path, { method: "HEAD" })) !== part.sha256
            )
              throw Error("Konflik chunk");
          }
        const result = await api(`/files/${record.id}`, {
          method: "PUT",
          body: JSON.stringify(record.envelope),
        });
        if (result.conflict) {
          const saved = await api(`/files/${record.id}`);
          for (const field of ["salt", "iv", "ciphertext"])
            if (saved[field] !== record.envelope[field])
              throw Error("Konflik backup");
        }
        count++;
        log(`Backup berhasil: ${value.name}`);
      } catch (error) {
        if (error.name === "AbortError") throw error;
        failed++;
        log(`Backup gagal: ${record.value.name} — ${error.message}`);
      }
    }
    active(session);
    status(
      failed
        ? `${count} berhasil, ${failed} gagal. Coba lagi untuk melanjutkan.`
        : `${count} file berhasil di-backup.`,
    );
  });
$("restore").onclick = () =>
  run(async (session, pass) => {
    const ids = await api("/files");
    if (!Array.isArray(ids)) throw Error("Daftar relay tidak valid");
    let count = 0,
      failed = 0;
    for (const id of ids) {
      try {
        active(session);
        if (!ID.test(id)) throw Error("ID relay tidak valid");
        const envelope = await api(`/files/${id}`),
          value = validate(await decrypt(envelope, pass), id);
        const existing = records.find((r) => r.id === id);
        if (
          existing &&
          !existing.value.unreadable &&
          ["salt", "iv", "ciphertext"].some(
            (field) => existing.envelope[field] !== envelope[field],
          )
        )
          throw Error("Konflik ID; salinan lokal dipertahankan");
        let complete = !!existing && !existing.value.unreadable;
        if (value.format === 2)
          for (let i = 0; i < value.parts.length; i++) {
            active(session);
            let data = await chunk(id, i);
            try {
              if (!data) throw Error();
              await openChunk(value, id, i, data);
            } catch {
              complete = false;
              data = await api(`/chunks/${id}/${i}`, {}, true);
              await openChunk(value, id, i, data);
              active(session);
              await putChunk(id, i, data);
            }
          }
        if (!complete) {
          active(session);
          await put(id, envelope);
          records = records.filter((r) => r.id !== id);
          records.push({ id, envelope, value: safeMetadata(value) });
          count++;
          log(`Pulih berhasil: ${value.name}`);
          render();
        }
      } catch (error) {
        if (error.name === "AbortError") throw error;
        failed++;
        log(`Pulih gagal: ${id.slice(0, 8)} — ${error.message}`);
      }
    }
    active(session);
    status(
      `${count} file dipulihkan ke perangkat ini.${failed ? ` ${failed} belum dapat dibuka/lengkap; periksa kata sandi dan riwayat.` : ""}`,
    );
  });
