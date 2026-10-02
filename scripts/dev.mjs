import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {resolve,extname} from 'node:path';
const root=process.cwd();
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png','.svg':'image/svg+xml','.webmanifest':'application/manifest+json','.json':'application/json'};
http.createServer(async(req,res)=>{try{const path=new URL(req.url,'http://localhost').pathname;
 if(path.startsWith('/api/')){if(!['/api/status','/api/ai','/api/ocr','/api/analyze','/api/twelvelabs','/api/check'].includes(path)){res.writeHead(404).end();return;}let body='';for await(const chunk of req){body+=chunk;if(body.length>4500000){res.writeHead(413).end();return;}}req.body=body;res.status=code=>(res.statusCode=code,res);res.json=value=>{res.setHeader('content-type','application/json');res.end(JSON.stringify(value));};const mod=await import('..'+path+'.js');await mod.default(req,res);return;}
 const file=resolve(root,'.'+(path==='/'?'/index.html':path));if(!file.startsWith(root+'/')){res.writeHead(403).end();return;}const data=await readFile(file);res.setHeader('Content-Type',types[extname(file)]||'application/octet-stream');res.end(data);
 }catch{res.writeHead(404).end('Não encontrado');}}).listen(3000,'0.0.0.0',()=>console.log('OSM AI Coach Pro em http://localhost:3000'));
