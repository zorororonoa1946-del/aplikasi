import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { access, writeFile, mkdir } from 'node:fs/promises';
process.chdir(new URL('..',import.meta.url).pathname);
const name=`nusa-smoke-${randomUUID().slice(0,8)}`,volume=`${name}-data`,caddy=`${name}-caddy`;
const env={...process.env,RELAY_TOKEN:'test-only-relay-token-123456789',VAULT_DOMAIN:'vault.example.com'};
if(!env.DOCKER_CONFIG&&await access('/workspace/toolchains/docker-config').then(()=>true,()=>false))env.DOCKER_CONFIG='/workspace/toolchains/docker-config';
const run=(args)=>new Promise((resolve,reject)=>{const child=spawn('docker',args,{env,stdio:['ignore','pipe','pipe']});let output='';for(const stream of [child.stdout,child.stderr])stream.on('data',data=>{output+=data;process.stdout.write(data);});child.on('error',reject);child.on('exit',code=>code===0?resolve(output):reject(Error(`Docker command failed (${code})`)));});
const token='test-only-relay-token-123456789';
const common=`import assert from 'node:assert/strict'; const base='http://127.0.0.1:3000';const headers={Authorization:'Bearer ${token}'}; let ready=false;for(let i=0;i<50;i++){try{ready=(await fetch(base)).ok;if(ready)break;}catch{}await new Promise(r=>setTimeout(r,100));}assert.ok(ready);`;
try{
 await run(['build','-t','nusa-vault:smoke','.']);
 await run(['compose','-f','deployment/compose.yaml','config','--quiet']);
 await run(['run','--detach','--name',name,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/tmp','--mount',`type=volume,source=${volume},target=/data`,'--env','RELAY_TOKEN','nusa-vault:smoke']);
 await run(['exec',name,'node','--input-type=module','-e',common+`assert.equal((await fetch(base+'/api/files')).status,401); const envelope={version:1,salt:Buffer.alloc(16).toString('base64'),iv:Buffer.alloc(12).toString('base64'),ciphertext:Buffer.alloc(16).toString('base64')};assert.equal((await fetch(base+'/api/files/12345678-1234-1234-1234-123456789abc',{method:'PUT',headers,body:JSON.stringify(envelope)})).status,201);console.log('PASS container authentication and persistent-volume write');`]);
 await run(['restart',name]);
 await run(['exec',name,'node','--input-type=module','-e',common+`assert.deepEqual(await(await fetch(base+'/api/files',{headers})).json(),['12345678-1234-1234-1234-123456789abc']);console.log('PASS backup survived container restart');`]);
 const image='caddy:2-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f';
 await run(['create','--name',caddy,'--env','VAULT_DOMAIN',image,'caddy','validate','--config','/etc/caddy/Caddyfile','--adapter','caddyfile']);
 await run(['cp','deployment/Caddyfile',`${caddy}:/etc/caddy/Caddyfile`]);await run(['start','--attach',caddy]);
 await mkdir('artifacts',{recursive:true});await writeFile('artifacts/container-test-results.txt',`${new Date().toISOString()}\nPASS Docker build; nonroot read-only relay; authentication; persistent volume across restart; Compose validation; Caddy HTTPS configuration validation. Public DNS/ACME deployment not exercised.\n`);
}finally{
 await run(['rm','--force',name]).catch(()=>{});await run(['rm','--force',caddy]).catch(()=>{});await run(['volume','rm',volume]).catch(()=>{});
}
