const $ = id => document.getElementById(id);
const encoder = new TextEncoder();
const decoder = new TextDecoder();
let password = '', records = [], generation = 0, lastActive = Date.now(), busy = false;
const db = await new Promise((resolve,reject) => { const request = indexedDB.open('nusa-vault',1); request.onupgradeneeded = () => request.result.createObjectStore('records'); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
function store(mode, callback) { return new Promise((resolve,reject) => { const tx = db.transaction('records',mode); const result = callback(tx.objectStore('records')); tx.oncomplete = () => resolve(result?.result); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error); }); }
const get = id => store('readonly', s => s.get(id));
const put = (id,data) => store('readwrite', s => s.put(data,id));
function base64(bytes) { let s = ''; for(let i=0;i<bytes.length;i+=8192) s += String.fromCharCode(...bytes.subarray(i,i+8192)); return btoa(s); }
const bytes = s => Uint8Array.from(atob(s), c=>c.charCodeAt(0));
async function key(pass,salt) { const material = await crypto.subtle.importKey('raw',encoder.encode(pass),'PBKDF2',false,['deriveKey']); return crypto.subtle.deriveKey({name:'PBKDF2',hash:'SHA-256',salt,iterations:600000},material,{name:'AES-GCM',length:256},false,['encrypt','decrypt']); }
async function encrypt(value,pass) { const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12)); const ciphertext = await crypto.subtle.encrypt({name:'AES-GCM',iv},await key(pass,salt),encoder.encode(JSON.stringify(value))); return {version:1,salt:base64(salt),iv:base64(iv),ciphertext:base64(new Uint8Array(ciphertext))}; }
async function decrypt(value,pass) { if(value.version !== 1) throw Error('Versi backup tidak didukung'); return JSON.parse(decoder.decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:bytes(value.iv)},await key(pass,bytes(value.salt)),bytes(value.ciphertext)))); }
function log(message) { const li = document.createElement('li'); li.textContent = `${new Date().toLocaleTimeString('id-ID')} · ${message}`; $('history').prepend(li); }
function status(message) { $('status').textContent = message; }
const size = n => n < 1024 ? `${n} B` : n < 1048576 ? `${(n/1024).toFixed(1)} KB` : `${(n/1048576).toFixed(1)} MB`;
function lock() { generation++; password = ''; records = []; $('token').value = ''; $('password').value = ''; $('files').replaceChildren(); $('history').replaceChildren(); $('status').textContent=''; $('search').value=''; $('file-count').textContent='0 file'; $('total-size').textContent='0 B'; $('vault').hidden = true; $('company').hidden = false; $('unlock-dialog').close(); }
function render() {
  $('file-count').textContent = `${records.length} file`; $('total-size').textContent = size(records.reduce((n,r)=>n+r.value.size,0));
  const visible = records.filter(r=>r.value.name.toLowerCase().includes($('search').value.toLowerCase())); $('files').replaceChildren();
  if(!visible.length) { const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = records.length ? 'Tidak ada file yang cocok.' : 'Vault masih kosong. Tambahkan file pilihan Anda untuk memulai.'; $('files').append(empty); }
  for(const record of visible) {
    const row = document.createElement('article'); row.className = 'file'; const info = document.createElement('div'), actions = document.createElement('div'), title = document.createElement('h3'), meta = document.createElement('small');
    title.textContent = record.value.name; meta.textContent = `${size(record.value.size)} · ${new Date(record.value.date).toLocaleDateString('id-ID')} · Terenkripsi`;
    info.append(title,meta);
    const download = document.createElement('button'); download.textContent = 'Unduh'; download.onclick = () => { const url = URL.createObjectURL(new Blob([bytes(record.value.data)],{type:'application/octet-stream'})); const a = document.createElement('a'); a.href = url; a.download = record.value.name; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000); };
    const remove = document.createElement('button'); remove.textContent = 'Hapus'; remove.onclick = () => run(async () => { if(!confirm('Hapus salinan lokal? Backup relay tetap disimpan.')) return; await store('readwrite',s=>s.delete(record.id)); records = records.filter(r=>r.id!==record.id); render(); log('Salinan lokal dihapus'); });
    actions.append(download,remove); row.append(info,actions); $('files').append(row);
  }
}
async function run(task) { if(busy || !password) return; busy = true; const session = generation; try { await task(session); } catch(e) { if(session===generation) status(`Gagal: ${e.message}`); } finally { busy = false; } }
async function api(path, options={}) { if(location.protocol !== 'https:' && !['localhost','127.0.0.1','[::1]'].includes(location.hostname)) throw Error('HTTPS diperlukan untuk koneksi relay'); const response = await fetch(`/api/files${path}`,{...options,headers:{'Authorization':`Bearer ${$('token').value}`,'Content-Type':'application/json'}}); const body = await response.json(); if(!response.ok) throw Error(body.error || 'Koneksi gagal'); return body; }
let taps = 0, tapTime = 0;
$('brand').onclick = async () => { if(Date.now()-tapTime>3000) taps=0; tapTime=Date.now(); if(++taps<7) return; taps=0; if(!crypto.subtle) { alert('Buka aplikasi melalui HTTPS atau localhost.'); return; } const exists=await get('check'); $('unlock-title').textContent=exists?'Buka vault':'Buat vault pribadi'; $('unlock-help').textContent=exists?'Masukkan kata sandi vault Anda.':'Pilih kata sandi minimal 12 karakter. Gunakan kata sandi yang sama saat memulihkan di laptop. Kata sandi tidak dapat direset.'; $('unlock-error').textContent=''; $('unlock-dialog').showModal(); $('password').focus(); };
$('cancel').onclick = () => { generation++; $('password').value=''; $('unlock-dialog').close(); };
$('unlock-dialog').addEventListener('cancel',()=>{generation++;$('password').value='';});
$('unlock-form').onsubmit = async event => {
  event.preventDefault();
  const pass=$('password').value, submit=event.submitter, attempt=generation;
  const active=()=>attempt===generation&&!document.hidden&&$('unlock-dialog').open;
  submit.disabled=true;
  try {
    const check=await get('check');
    if(check) { const value=await decrypt(check,pass); if(value.check!=='nusa-vault')throw Error(); }
    else { const verifier=await encrypt({check:'nusa-vault'},pass); if(!active())return; await put('check',verifier); }
    const ids=await store('readonly',s=>s.getAllKeys()), loaded=[];
    for(const id of ids.filter(id=>id!=='check')) { const envelope=await get(id); const value=await decrypt(envelope,pass); if(!active())return; loaded.push({id,envelope,value}); }
    if(!active())return;
    password=pass; generation++; records=loaded; lastActive=Date.now(); $('password').value=''; $('unlock-dialog').close();
    $('company').hidden=true; $('vault').hidden=false; status('Vault terbuka. Kunci otomatis setelah 3 menit tidak aktif.'); render();
  } catch { if(active())$('unlock-error').textContent='Vault tidak dapat dibuka. Periksa kata sandi atau data lokal.'; }
  finally { submit.disabled=false; }
};
$('lock').onclick=lock;
$('search').oninput=render;
for(const event of ['pointerdown','keydown','input']) document.addEventListener(event,()=>lastActive=Date.now());
document.addEventListener('visibilitychange',()=>{if(document.hidden){if(password)lock();else if($('unlock-dialog').open){generation++;$('password').value='';$('unlock-dialog').close();}}});
setInterval(()=>{if(password && Date.now()-lastActive>180000) lock();},1000);
$('import').onchange=event=> { const files=[...event.target.files]; event.target.value=''; run(async session=>{const pass=password; for(const file of files) { if(file.size>20*1024*1024) throw Error('Batas per file 20 MB'); status(`Mengenkripsi ${file.name}…`); const value={name:file.name,size:file.size,date:new Date().toISOString(),data:base64(new Uint8Array(await file.arrayBuffer()))}; const envelope=await encrypt(value,pass); if(session!==generation)return; const id=crypto.randomUUID(); await put(id,envelope); if(session!==generation)return; records.push({id,value,envelope}); render(); log(`Ditambahkan: ${file.name}`); } status('File tersimpan terenkripsi di browser ini. Backup ke relay untuk salinan tambahan.'); }); };
$('backup').onclick=()=>run(async session=>{if(!records.length) return status('Tambahkan file terlebih dahulu.'); let count=0; for(const r of [...records]) { if(session!==generation)return; try { await api(`/${r.id}`,{method:'PUT',body:JSON.stringify(r.envelope)}); } catch(e) { if(!e.message.includes('ID sudah tersedia')) throw e; const existing=await api(`/${r.id}`); if(JSON.stringify(existing)!==JSON.stringify(r.envelope)) throw Error('Konflik backup: isi berbeda untuk ID yang sama'); } count++; } if(session===generation) {status(`${count} file berhasil di-backup.`); log(`Backup selesai: ${count} file`);} });
$('restore').onclick=()=>run(async session=>{const pass=password; const ids=await api(''); let count=0; for(const id of ids) { if(!/^[a-f0-9-]{36}$/.test(id)) throw Error('ID relay tidak valid'); if(records.some(r=>r.id===id))continue; const envelope=await api(`/${id}`); let value; try {value=await decrypt(envelope,pass);} catch {throw Error('Kata sandi backup berbeda atau data rusak; salinan yang ada tetap disimpan');} if(typeof value.name!=='string'||typeof value.data!=='string'||!Number.isFinite(value.size))throw Error('Isi backup tidak valid'); if(session!==generation)return; await put(id,envelope); if(session!==generation)return; records.push({id,envelope,value}); count++; render(); } if(session===generation){status(`${count} file dipulihkan ke perangkat ini.`); log(`Pemulihan selesai: ${count} file`);} });
