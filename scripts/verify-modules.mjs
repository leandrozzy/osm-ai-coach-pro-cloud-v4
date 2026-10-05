import {readdir,readFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import vm from 'node:vm';
const {createContext,SourceTextModule,SyntheticModule}=vm;

// Linking checks named imports and re-exports without executing browser code,
// loading saved data, starting workers or calling any provider API.
export async function verifyModules(root=process.cwd()){
 if(!SourceTextModule)throw new Error('Execute com node --experimental-vm-modules scripts/verify-modules.mjs');
 const base=resolve(root),context=createContext({}),modules=new Map();
 async function moduleAt(identifier){
  if(modules.has(identifier))return modules.get(identifier);
  if(identifier.startsWith('node:')){
   const namespace=await import(identifier),keys=Object.keys(namespace);
   const module=new SyntheticModule(keys,function(){for(const key of keys)this.setExport(key,namespace[key]);},{identifier,context});
   modules.set(identifier,module);return module;
  }
  const source=await readFile(fileURLToPath(identifier),'utf8');
  const module=new SourceTextModule(source,{identifier,context});
  modules.set(identifier,module);return module;
 }
 function dependency(specifier,referencingModule){
  if(specifier.startsWith('node:'))return moduleAt(specifier);
  if(!specifier.startsWith('.')&&!specifier.startsWith('/'))throw new Error(`Unsupported module import ${specifier} in ${referencingModule.identifier}`);
  return moduleAt(new URL(specifier,referencingModule.identifier).href);
 }
 const entries=[];
 async function collect(directory){
  const files=await readdir(directory,{withFileTypes:true}).catch(error=>{if(error.code==='ENOENT')return [];throw error;});
  for(const file of files){const path=join(directory,file.name);if(file.isDirectory())await collect(path);else if(/\.m?js$/.test(file.name))entries.push(path);}
 }
 for(const directory of ['src','api','lib'])await collect(join(base,directory));
 const rootFiles=await readdir(base,{withFileTypes:true});
 for(const file of rootFiles)if(file.isFile()&&/\.m?js$/.test(file.name))entries.push(join(base,file.name));
 for(const entry of entries)await moduleAt(pathToFileURL(entry).href);
 for(const module of [...modules.values()])if(module.status==='unlinked')await module.link(dependency);
 return {entries:entries.length,modules:modules.size};
}

if(process.argv[1]&&fileURLToPath(import.meta.url)===resolve(process.argv[1])){
 try{const result=await verifyModules(process.argv[2]);console.log(`Contratos de módulos verificados: ${result.modules} módulos, sem executar o app.`);}
 catch(error){console.error(`Contrato de módulos inválido: ${error.message}`);process.exitCode=1;}
}
