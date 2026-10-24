import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from '../server.js';
test('relay authenticates, stores ciphertext, restores it, and protects existing backups', async () => {
 const dir=await mkdtemp(join(tmpdir(),'vault-test-')); const token='test-only-token-with-32-characters'; const server=createServer({token,dataDir:dir,maxBytes:1024}); await new Promise(r=>server.listen(0,'127.0.0.1',r)); const origin=`http://127.0.0.1:${server.address().port}`;
 const request=(path,options={})=>fetch(origin+path,{...options,headers:{Authorization:`Bearer ${token}`,...options.headers}});
 try {
  assert.equal((await fetch(origin+'/api/files')).status,401);
  assert.equal((await request('/')).status,200);
  assert.equal((await request('/server.js')).status,404);
  const id='12345678-1234-1234-1234-123456789abc';
  const envelope={version:1,salt:Buffer.alloc(16).toString('base64'),iv:Buffer.alloc(12).toString('base64'),ciphertext:Buffer.alloc(16).toString('base64')};
  for (const invalid of [null, {}, {...envelope,iv:'YWJj'}, {...envelope,ciphertext:'c2VjcmV0'}, {...envelope,salt:'!!!!'}]) {
   assert.equal((await request('/api/files/'+id,{method:'PUT',body:JSON.stringify(invalid)})).status,400);
  }
  assert.equal((await request('/api/files/'+id,{method:'PUT',body:JSON.stringify(envelope)})).status,201);
  assert.equal((await request('/api/files/'+id,{method:'PUT',body:JSON.stringify(envelope)})).status,409);
  assert.deepEqual(await (await request('/api/files')).json(),[id]);
  assert.deepEqual(await (await request('/api/files/'+id)).json(),envelope);
  assert.equal((await request('/api/files/'+id,{method:'PUT',body:'x'.repeat(1025)})).status,413);
  const concurrentId='22345678-1234-1234-1234-123456789abc';
  const statuses=await Promise.all(Array.from({length:5},async()=> (await request('/api/files/'+concurrentId,{method:'PUT',body:JSON.stringify(envelope)})).status));
  assert.deepEqual(statuses.sort(),[201,409,409,409,409]);
  assert.deepEqual(await (await request('/api/files/'+concurrentId)).json(),envelope);
  assert.equal((await request('/api/files/'+id,{method:'DELETE'})).status,200);
  assert.equal((await request('/api/files/'+id)).status,404);
 } finally {await new Promise(r=>server.close(r)); await rm(dir,{recursive:true,force:true});}
});
test('unconfigured relay refuses data access',async()=>{const server=createServer({token:''});await new Promise(r=>server.listen(0,'127.0.0.1',r));try{assert.equal((await fetch(`http://127.0.0.1:${server.address().port}/api/files`)).status,503);}finally{await new Promise(r=>server.close(r));}});
