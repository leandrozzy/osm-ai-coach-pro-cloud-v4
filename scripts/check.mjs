import {readdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
for(const dir of ['src','api','lib','scripts'])for(const file of await readdir(dir)){if(!/\.(m?js)$/.test(file))continue;const result=spawnSync(process.execPath,['--check',dir+'/'+file],{stdio:'inherit'});if(result.status)process.exit(result.status);}
for(const args of [['--check','app.js'],['--check','sw.js'],['--test']]){const result=spawnSync(process.execPath,args,{stdio:'inherit'});if(result.status)process.exit(result.status);}
