import http from 'node:http';
import { readFile, mkdir, open, link, readdir, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { timingSafeEqual, createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('./public/', import.meta.url));
export function createServer({ token = process.env.RELAY_TOKEN, dataDir = process.env.DATA_DIR || '.vault-data', maxBytes = 40 * 1024 * 1024 } = {}) {
  const directory = resolve(dataDir);
  return http.createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    const reply = (status, body) => { if(res.destroyed || res.writableEnded) return; res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(body)); };
    try {
      const path = new URL(req.url, 'http://localhost').pathname;
      if (path.startsWith('/api/')) {
        if (!token || token.length < 24) return reply(503, {error:'Relay belum dikonfigurasi. Atur RELAY_TOKEN minimal 24 karakter.'});
        const hash = value => createHash('sha256').update(value).digest();
        if (!timingSafeEqual(hash(req.headers.authorization || ''), hash(`Bearer ${token}`))) return reply(401, {error:'Kredensial relay salah.'});
        await mkdir(directory, {recursive:true, mode:0o700});
        if (path === '/api/files' && req.method === 'GET') return reply(200, (await readdir(directory)).filter(x=>/^[a-f0-9-]{36}\.json$/.test(x)).map(x=>x.slice(0,-5)));
        const match = path.match(/^\/api\/files\/([a-f0-9-]{36})$/);
        if (!match) return reply(404, {error:'Tidak ditemukan'});
        const file = join(directory, `${match[1]}.json`);
        if (req.method === 'GET') { const data = await readFile(file); res.writeHead(200, {'Content-Type':'application/json'}); return res.end(data); }
        if (req.method === 'DELETE') { await unlink(file); return reply(200, {ok:true}); }
        if (req.method === 'PUT') {
          let size = 0; const chunks = [];
          for await (const chunk of req) { size += chunk.length; if(size > maxBytes) { reply(413,{error:'Batas unggahan terlampaui'}); return; } chunks.push(chunk); }
          let envelope; try { envelope = JSON.parse(Buffer.concat(chunks).toString()); } catch { return reply(400,{error:'JSON tidak valid'}); }
          if (!envelope || envelope.version !== 1 || !['salt','iv','ciphertext'].every(k=>typeof envelope[k] === 'string' && Buffer.from(envelope[k], 'base64').toString('base64') === envelope[k]) || Buffer.from(envelope.salt,'base64').length !== 16 || Buffer.from(envelope.iv,'base64').length !== 12 || Buffer.from(envelope.ciphertext,'base64').length < 16) return reply(400,{error:'Format enkripsi tidak valid'});
          const temporary = join(directory, `.upload-${randomUUID()}`);
          try {
            const handle = await open(temporary, 'wx', 0o600);
            try {
              await handle.writeFile(JSON.stringify({version:1,salt:envelope.salt,iv:envelope.iv,ciphertext:envelope.ciphertext}));
              await handle.sync();
            } finally { await handle.close(); }
            // Publish only complete data; link refuses to overwrite an existing backup.
            await link(temporary, file);
          }
          catch(e) { if(e.code === 'EEXIST') return reply(409,{error:'ID sudah tersedia; backup lama dipertahankan'}); throw e; }
          finally { await unlink(temporary).catch(() => {}); }
          return reply(201,{ok:true});
        }
        return reply(405,{error:'Metode tidak didukung'});
      }
      if (req.method !== 'GET') return reply(405,{error:'Metode tidak didukung'});
      const assets = Object.assign(Object.create(null), {'/':'index.html','/app.js':'app.js','/style.css':'style.css'});
      if (!assets[path]) return reply(404,{error:'Tidak ditemukan'});
      const type = path.endsWith('.js') ? 'text/javascript' : path.endsWith('.css') ? 'text/css' : 'text/html';
      res.writeHead(200, {'Content-Type':`${type}; charset=utf-8`}); res.end(await readFile(join(root,assets[path])));
    } catch(e) { reply(e.code === 'ENOENT' ? 404 : 500, {error:e.code === 'ENOENT' ? 'Tidak ditemukan' : 'Operasi gagal'}); }
  });
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) createServer().listen(Number(process.env.PORT || 3000), process.env.HOST || '127.0.0.1', () => console.log('Personal Vault listening on port', process.env.PORT || 3000));
