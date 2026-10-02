import {mkdir,copyFile,readdir,rm} from 'node:fs/promises';
import {join} from 'node:path';
const out='dist';await rm(out,{recursive:true,force:true});await mkdir(out);
async function copy(path){const entries=await readdir(path,{withFileTypes:true});for(const e of entries){const src=join(path,e.name),target=join(out,src);if(e.isDirectory()){await mkdir(target,{recursive:true});await copy(src);}else await copyFile(src,target);}}
for(const f of ['index.html','styles.css','app.js','sw.js','manifest.webmanifest'])await copyFile(f,join(out,f));
for(const dir of ['src','assets']){await mkdir(join(out,dir));await copy(dir);}
console.log('PWA estática criada em dist. APIs permanecem em api/ para Vercel.');
