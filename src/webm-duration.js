// MediaRecorder WebM is a streaming container and commonly omits Info.Duration.
// Read EBML element boundaries, never search compressed frame bytes for markers.
const ID={EBML:0x1a45dfa3,SEGMENT:0x18538067,INFO:0x1549a966,SCALE:0x2ad7b1,DURATION:0x4489,DOCTYPE:0x4282,CLUSTER:0x1f43b675,TIMECODE:0xe7,SIMPLE_BLOCK:0xa3,BLOCK_GROUP:0xa0,BLOCK:0xa1,SEEK:0x114d9b74,CUES:0x1c53bb6b,POSITION:0xa7,CRC:0xbf,VOID:0xec};
const LEVEL_ONE=new Set([ID.INFO,ID.CLUSTER,ID.SEEK,ID.CUES,0x1654ae6b,0x1941a469,0x1043a770,0x1254c367]);
const failure=()=>Error('O vídeo preparado não tem uma duração verificável. Use Google/OCR.space ou envie o vídeo original de até 3 MB; os dados já lidos foram preservados.');
const cancelled=()=>Object.assign(Error('Envio TwelveLabs cancelado.'),{code:'ABORTED'});
const abort=signal=>{if(signal?.aborted)throw cancelled();};
function vintWidth(first,max){
 let bit=0x80;
 for(let width=1;width<=max;width++,bit>>=1)if(first&bit)return width;
 throw failure();
}
function element(bytes,start,limit=bytes.length){
 if(start>=limit)throw failure();
 const idWidth=vintWidth(bytes[start],4);
 if(start+idWidth>=limit)throw failure();
 let id=0;for(let i=0;i<idWidth;i++)id=id*256+bytes[start+i];
 const sizeStart=start+idWidth,sizeWidth=vintWidth(bytes[sizeStart],8),dataStart=sizeStart+sizeWidth;
 if(dataStart>limit)throw failure();
 const mask=(1<<(8-sizeWidth))-1;
 let size=bytes[sizeStart]&mask,unknown=size===mask;
 for(let i=1;i<sizeWidth;i++){unknown=unknown&&bytes[sizeStart+i]===255;size=size*256+bytes[sizeStart+i];}
 if(!unknown&&(!Number.isSafeInteger(size)||size>limit-dataStart))throw failure();
 return {id,start,idWidth,sizeStart,sizeWidth,dataStart,size:unknown?null:size,end:unknown?null:dataStart+size};
}
function finiteChildren(bytes,start,end){
 const children=[];let position=start;
 while(position<end){
  if(children.length>100000)throw failure();
  const child=element(bytes,position,end);
  if(child.end===null)throw failure();
  children.push(child);position=child.end;
 }
 if(position!==end)throw failure();
 return children;
}
function segmentChildren(bytes,segment,end){
 const children=[];let position=segment.dataStart;
 while(position<end){
  if(children.length>100000)throw failure();
  let child=element(bytes,position,end);
  if(child.end===null){
   if(child.id!==ID.CLUSTER)throw failure();
   let boundary=child.dataStart;
   while(boundary<end){
    const nested=element(bytes,boundary,end);
    if(LEVEL_ONE.has(nested.id))break;
    if(nested.end===null)throw failure();
    boundary=nested.end;
   }
   child={...child,end:boundary};
  }
  children.push(child);position=child.end;
 }
 if(position!==end)throw failure();
 return children;
}
function unsigned(bytes,start,end){
 if(end<=start||end-start>8)throw failure();
 let number=0;for(let i=start;i<end;i++)number=number*256+bytes[i];
 if(!Number.isSafeInteger(number))throw failure();
 return number;
}
function sizeBytes(size,width=1){
 while(size>=2**(7*width)-1)width++;
 if(width>8||!Number.isSafeInteger(size)||size<0)throw failure();
 const bytes=new Uint8Array(width);let n=size;
 for(let i=width-1;i>=0;i--){bytes[i]=n%256;n=Math.floor(n/256);}
 bytes[0]|=1<<(8-width);return bytes;
}
function unknownSize(width){
 const bytes=new Uint8Array(width).fill(255);bytes[0]=(1<<(9-width))-1;return bytes;
}
function join(parts){
 const length=parts.reduce((sum,part)=>sum+part.length,0),bytes=new Uint8Array(length);let offset=0;
 for(const part of parts){bytes.set(part,offset);offset+=part.length;}
 return bytes;
}
function voidElement(length){
 for(let width=1;width<=8;width++){
  const payload=length-1-width;
  if(payload>=0&&payload<2**(7*width)-1)return join([Uint8Array.of(ID.VOID),sizeBytes(payload,width),new Uint8Array(payload)]);
 }
 throw failure();
}
function blockTime(bytes,block,timecode){
 const width=vintWidth(bytes[block.dataStart],8);
 if(block.dataStart+width+3>=block.end)throw failure();
 const start=block.dataStart+width;
 const relative=new DataView(bytes.buffer,bytes.byteOffset+start,2).getInt16(0,false);
 return timecode+relative;
}
function durationInfo(bytes,info,durationSeconds,scale){
 const fields=finiteChildren(bytes,info.dataStart,info.end);
 const payload=fields.filter(field=>field.id!==ID.DURATION&&field.id!==ID.CRC).map(field=>bytes.subarray(field.start,field.end));
 const value=new Uint8Array(8);new DataView(value.buffer).setFloat64(0,durationSeconds*1e9/scale,false);
 payload.push(join([Uint8Array.of(0x44,0x89,0x88),value]));
 const data=join(payload);
 return join([bytes.subarray(info.start,info.sizeStart),sizeBytes(data.length,info.sizeWidth),data]);
}
function cleanCluster(bytes,cluster){
 const fields=finiteChildren(bytes,cluster.dataStart,cluster.end);
 const payload=fields.map(field=>field.id===ID.POSITION||field.id===ID.CRC?voidElement(field.end-field.start):bytes.subarray(field.start,field.end));
 return join([bytes.subarray(cluster.start,cluster.dataStart),...payload]);
}

export function repairWebmDurationBytes(input,expectedSeconds){
 const bytes=input instanceof Uint8Array?input:new Uint8Array(input);
 if(!Number.isFinite(expectedSeconds)||expectedSeconds<1||expectedSeconds>3600||bytes.length<32)throw failure();
 const header=element(bytes,0);
 if(header.id!==ID.EBML||header.end===null)throw failure();
 const doc=finiteChildren(bytes,header.dataStart,header.end).find(field=>field.id===ID.DOCTYPE);
 if(!doc||!['webm','matroska'].includes(new TextDecoder().decode(bytes.subarray(doc.dataStart,doc.end))))throw failure();
 let position=header.end,segment;
 while(position<bytes.length){
  const next=element(bytes,position);
  if(next.id===ID.SEGMENT){segment=next;break;}
  if(next.id!==ID.VOID||next.end===null)throw failure();
  position=next.end;
 }
 if(!segment||segment.end!==null&&segment.end!==bytes.length)throw failure();
 const end=segment.end??bytes.length,children=segmentChildren(bytes,segment,end);
 const tracks=children.find(child=>child.id===0x1654ae6b);
 if(!tracks||tracks.size===null||!finiteChildren(bytes,tracks.dataStart,tracks.end).some(track=>track.id===0xae&&finiteChildren(bytes,track.dataStart,track.end).some(field=>field.id===0x83&&unsigned(bytes,field.dataStart,field.end)===1)))throw failure();
 const infos=children.filter(child=>child.id===ID.INFO);
 if(infos.length!==1||infos[0].size===null)throw failure();
 const info=infos[0],scaleField=finiteChildren(bytes,info.dataStart,info.end).find(field=>field.id===ID.SCALE);
 const scale=scaleField?unsigned(bytes,scaleField.dataStart,scaleField.end):1000000;
 if(scale<=0||scale>1000000000)throw failure();
 const timestamps=[];
 for(const cluster of children.filter(child=>child.id===ID.CLUSTER)){
  const fields=finiteChildren(bytes,cluster.dataStart,cluster.end);
  const time=fields.find(field=>field.id===ID.TIMECODE);
  if(!time)throw failure();
  const ticks=unsigned(bytes,time.dataStart,time.end);
  for(const field of fields){
   if(field.id===ID.SIMPLE_BLOCK)timestamps.push(blockTime(bytes,field,ticks)*scale/1e9);
   else if(field.id===ID.BLOCK_GROUP){
    const block=finiteChildren(bytes,field.dataStart,field.end).find(child=>child.id===ID.BLOCK);
    if(block)timestamps.push(blockTime(bytes,block,ticks)*scale/1e9);
   }
  }
 }
 // Duration metadata alone cannot turn an empty/zero-timestamp stream into a video.
 const sorted=[...new Set(timestamps)].sort((a,b)=>a-b);
 if(sorted.length<2||sorted.at(-1)-sorted[0]<1||sorted.at(-1)<expectedSeconds*.7)throw failure();
 const gaps=sorted.slice(1).map((time,index)=>time-sorted[index]).filter(gap=>gap>0&&gap<2).sort((a,b)=>a-b);
 const lastFrameSeconds=gaps.length?gaps[Math.floor(gaps.length/2)]:.5;
 const durationSeconds=Math.max(expectedSeconds,sorted.at(-1)+lastFrameSeconds);
 if(durationSeconds>3600||sorted[0]<-1)throw failure();
 const rewritten=children.map(child=>{
  if(child.id===ID.INFO)return durationInfo(bytes,child,durationSeconds,scale);
  // A larger Info changes downstream offsets. Remove indexes and obsolete
  // absolute cluster positions rather than retaining invalid seek addresses.
  if([ID.SEEK,ID.CUES,ID.CRC].includes(child.id))return voidElement(child.end-child.start);
  if(child.id===ID.CLUSTER)return cleanCluster(bytes,child);
  return bytes.subarray(child.start,child.end);
 });
 const output=join([bytes.subarray(0,segment.sizeStart),unknownSize(segment.sizeWidth),...rewritten]);
 return {bytes:output,durationSeconds,frameCount:timestamps.length};
}

export async function repairWebmDuration(blob,expectedSeconds,signal){
 abort(signal);const bytes=new Uint8Array(await blob.arrayBuffer());abort(signal);
 const repaired=repairWebmDurationBytes(bytes,expectedSeconds);abort(signal);
 return new Blob([repaired.bytes],{type:'video/webm'});
}
