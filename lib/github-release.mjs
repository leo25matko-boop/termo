import {createHash,randomUUID} from 'node:crypto';
import {createReadStream,createWriteStream} from 'node:fs';
import {mkdir,readFile,writeFile,rename,unlink,open,stat} from 'node:fs/promises';
import path from 'node:path';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';

const MAX_ZIP = 250 * 1024 * 1024;
const HOSTS = new Set(['api.github.com','github.com','release-assets.githubusercontent.com','objects.githubusercontent.com']);
export function repositoryName(value) {
  let name=String(value||'').trim();
  if(name.startsWith('https://')) {
    const url=new URL(name);
    if(url.hostname!=='github.com'||url.username||url.password||url.search||url.hash||url.port)throw new Error('Unesite poveznicu GitHub repozitorija.');
    name=url.pathname.replace(/^\/+|\/+$/g,'').replace(/\.git$/,'');
  }
  if(!/^[a-z\d](?:[a-z\d-]{0,38})\/[a-z\d._-]{1,100}$/i.test(name)||name.split('/')[1]==='.'||name.split('/')[1]==='..')throw new Error('Repozitorij mora imati oblik vlasnik/naziv ili https://github.com/vlasnik/naziv.');
  return name;
}
function safeUrl(value) {
  const url=new URL(value);
  if(url.protocol!=='https:'||!HOSTS.has(url.hostname)||url.username||url.password||url.hash||(url.port&&url.port!=='443'))throw new Error('GitHub je vratio nedopuštenu adresu preuzimanja.');
  return url;
}
async function request(url,{fetchImpl,token,accept,signal}) {
  let current=safeUrl(url);
  for(let hops=0;hops<=5;hops++) {
    const headers={'User-Agent':'TermoDom/1.0','Accept':accept};
    if(current.hostname==='api.github.com') {
      headers['X-GitHub-Api-Version']='2022-11-28';
      if(token)headers.Authorization='Bearer '+token;
    }
    const response=await fetchImpl(current,{headers,redirect:'manual',signal});
    if([301,302,303,307,308].includes(response.status)) {
      const location=response.headers.get('location');
      await response.body?.cancel();
      if(!location)throw new Error('Nedostaje GitHub adresa preusmjeravanja.');
      current=safeUrl(new URL(location,current));
      continue;
    }
    if(!response.ok) {
      await response.body?.cancel();
      if(response.status===404)throw new Error('Repozitorij ili objavljeni ZIP još nije dostupan na GitHubu. Za privatni repozitorij potreban je pristup.');
      if(response.status===403||response.status===429)throw new Error('GitHub je ograničio pristup ili broj zahtjeva. Pokušajte kasnije.');
      throw new Error('GitHub je vratio HTTP '+response.status+'.');
    }
    return response;
  }
  throw new Error('GitHub je poslao previše preusmjeravanja.');
}
async function jsonResponse(response) {
  let size=0;const parts=[];
  for await(const chunk of response.body){size+=chunk.length;if(size>2*1024*1024)throw new Error('GitHub odgovor je prevelik.');parts.push(chunk)}
  try{return JSON.parse(Buffer.concat(parts).toString('utf8'))}catch{throw new Error('GitHub nije vratio valjane podatke o izdanju.')}
}
async function checksum(filename) {
  const hash=createHash('sha256');for await(const chunk of createReadStream(filename))hash.update(chunk);return hash.digest('hex');
}
async function readJson(filename) {try{return JSON.parse(await readFile(filename,'utf8'))}catch{return null}}

// Downloads only. This module never extracts or executes the downloaded archive.
export async function downloadLatest(config,{directory,fetchImpl=globalThis.fetch,token=null}={}) {
  if(!config?.repository)return {status:'not-configured',message:'GitHub repozitorij nije postavljen.'};
  const repo=repositoryName(config.repository);
  const assetName=config.assetName||'TermoDom-Windows.zip';
  if(!/^[a-z\d][a-z\d._-]*\.zip$/i.test(assetName))throw new Error('Naziv ZIP paketa nije valjan.');
  const folder=path.resolve(directory,'downloads');await mkdir(folder,{recursive:true});
  const lockPath=path.join(folder,'.github-download.lock');let lock;
  try{lock=await open(lockPath,'wx')}catch(e){
    if(e.code!=='EEXIST')throw e;
    const info=await stat(lockPath).catch(()=>null);
    if(info&&Date.now()-info.mtimeMs>10*60*1000){await unlink(lockPath);lock=await open(lockPath,'wx')}
    else return {status:'busy',message:'Preuzimanje s GitHuba već je u tijeku.'};
  }
  let partial;
  try {
    await lock.writeFile(String(process.pid));
    const metadata=await request(`https://api.github.com/repos/${repo}/releases/latest`,{fetchImpl,token,accept:'application/vnd.github+json',signal:AbortSignal.timeout(20000)});
    const release=await jsonResponse(metadata);
    if(release.draft||release.prerelease)throw new Error('Najnovije stabilno izdanje još nije objavljeno.');
    const asset=release.assets?.find(a=>a.name===assetName&&a.state==='uploaded');
    if(!asset)throw new Error(`Izdanje nema ZIP datoteku ${assetName}. Potreban je paket aplikacije iz odjeljka Releases.`);
    if(!Number.isSafeInteger(asset.id)||asset.id<1||!Number.isSafeInteger(asset.size)||asset.size<22||asset.size>MAX_ZIP)throw new Error('Veličina ili identifikator GitHub ZIP paketa nije valjan.');
    const expected=asset.digest==null?null:/^sha256:[a-f\d]{64}$/i.test(asset.digest)?asset.digest.slice(7).toLowerCase():null;
    if(asset.digest&&!expected)throw new Error('GitHub nije vratio podržanu kontrolnu sumu paketa.');
    const destination=path.join(folder,assetName),stateFile=path.join(folder,'github-download.json');
    const previous=await readJson(stateFile);
    if(previous?.repository===repo&&previous.assetId===asset.id&&previous.updatedAt===asset.updated_at&&previous.assetName===assetName&&previous.sha256&&(!expected||previous.sha256===expected)) {
      try{const info=await stat(destination);if(info.size===asset.size&&await checksum(destination)===previous.sha256)return {status:'up-to-date',message:'Najnoviji GitHub ZIP već je preuzet.',file:destination,tag:release.tag_name}}catch{}
    }
    const response=await request(`https://api.github.com/repos/${repo}/releases/assets/${asset.id}`,{fetchImpl,token,accept:'application/octet-stream',signal:AbortSignal.timeout(120000)});
    partial=path.join(folder,'.'+randomUUID()+'.part');
    let bytes=0,magic=Buffer.alloc(0);const hash=createHash('sha256');
    async function* checkedChunks(source) {
      for await(const chunk of source) {
        const value=Buffer.from(chunk);bytes+=value.length;
        if(bytes>asset.size||bytes>MAX_ZIP)throw new Error('ZIP paket premašuje očekivanu veličinu.');
        if(magic.length<4)magic=Buffer.concat([magic,value]).subarray(0,4);
        hash.update(value);yield value;
      }
    }
    if(!response.body)throw new Error('GitHub nije vratio sadržaj ZIP datoteke.');
    await pipeline(Readable.from(checkedChunks(response.body)),createWriteStream(partial,{flags:'wx'}));
    if(bytes!==asset.size)throw new Error('ZIP datoteka nije potpuno preuzeta.');
    if(!['504b0304','504b0506','504b0708'].includes(magic.toString('hex')))throw new Error('Preuzeta datoteka nije ZIP paket.');
    const sha256=hash.digest('hex');
    if(expected&&sha256!==expected)throw new Error('Kontrolna suma ZIP paketa ne odgovara GitHub izdanju.');
    await rename(partial,destination);partial=null;
    const record={repository:repo,tag:release.tag_name,assetName,assetId:asset.id,updatedAt:asset.updated_at,sha256,size:bytes,downloadedAt:new Date().toISOString()};
    const statePart=path.join(folder,'.'+randomUUID()+'.json');
    try{await writeFile(statePart,JSON.stringify(record,null,2));await rename(statePart,stateFile)}finally{await unlink(statePart).catch(()=>{})}
    return {status:'downloaded',message:'GitHub ZIP je preuzet.',file:destination,tag:release.tag_name,sha256};
  }finally{
    if(partial)await unlink(partial).catch(()=>{});
    await lock.close();await unlink(lockPath).catch(()=>{});
  }
}
