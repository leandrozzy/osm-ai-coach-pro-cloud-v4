import {mkdir,copyFile,readdir,rm,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {verifyModules} from './verify-modules.mjs';
await verifyModules();
const out='dist',digest=bytes=>createHash('sha256').update(bytes).digest('hex');await rm(out,{recursive:true,force:true});await mkdir(out);
async function copy(path){const entries=await readdir(path,{withFileTypes:true});for(const e of entries){const src=join(path,e.name),target=join(out,src);if(e.isDirectory()){await mkdir(target,{recursive:true});await copy(src);}else await copyFile(src,target);}}
for(const f of ['index.html','styles.css','app.js','sw.js','manifest.webmanifest'])await copyFile(f,join(out,f));
for(const dir of ['src','assets']){await mkdir(join(out,dir));await copy(dir);}
const names=(await readdir('src')).filter(name=>name.endsWith('.js')).sort().map(name=>'src/'+name);
const releaseNames=[...names,'styles.css'],stableNames=['index.html','app.js','manifest.webmanifest',...(await readdir('assets')).sort().map(name=>'assets/'+name)];
const contents=new Map();for(const name of [...releaseNames,...stableNames,'sw.js'])contents.set(name,await readFile(name));
const identity=createHash('sha256');for(const [name,bytes]of [...contents].sort(([a],[b])=>a.localeCompare(b)))identity.update(name+'\0').update(bytes).update('\0');
const id=identity.digest('hex'),prefix='/releases/'+id,assets=[];
for(const name of releaseNames){const target=join(out,prefix,name);await mkdir(join(target,'..'),{recursive:true});await writeFile(target,contents.get(name));assets.push({url:prefix+'/'+name,sha256:digest(contents.get(name)),bytes:contents.get(name).length});}
for(const name of stableNames)assets.push({url:'/'+name,sha256:digest(contents.get(name)),bytes:contents.get(name).length});
const release={version:1,id,entry:prefix+'/src/ui.js',style:prefix+'/styles.css',shell:'/index.html',assets};
await writeFile(join(out,'release.json'),JSON.stringify(release));
await writeFile(join(out,'sw.js'),contents.get('sw.js').toString().replace('__OSM_RELEASE_ID__',id));
console.log('PWA criada em dist. Versão '+id.slice(0,12)+', '+assets.length+' arquivos verificados. APIs permanecem em api/.');
