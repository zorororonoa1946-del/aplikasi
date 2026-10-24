import { randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
const domain=process.argv[2];
if(!domain || !/^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$/.test(domain)) {
 console.error('Usage: npm run relay:init -- vault.example.com');process.exit(1);
}
const file=new URL('../deployment/.env',import.meta.url);
try { await writeFile(file,`VAULT_DOMAIN=${domain}\nRELAY_TOKEN=${randomBytes(32).toString('base64url')}\n`,{flag:'wx',mode:0o600});console.log('Created deployment/.env with a random relay token. Read it privately to configure your Android app and laptop; never commit this file.'); }
catch(e){if(e.code==='EEXIST'){console.error('deployment/.env already exists; preserved without modification.');process.exit(1);}throw e;}
