// Keep faint OSM labels readable without changing the original preview/visual AI image.
function adjustedCanvas(source,{x=0,y=0,width=source.width,height=source.height,upscale=1,report=false}={}){
 const scale=Math.min(upscale,2448/Math.max(width,height));
 const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(width*scale));canvas.height=Math.max(1,Math.round(height*scale));
 const ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(source,x,y,width,height,0,0,canvas.width,canvas.height);
 const pixels=ctx.getImageData(0,0,canvas.width,canvas.height),histogram=new Uint32Array(256);
 for(let i=0;i<pixels.data.length;i+=4){const gray=Math.round(.299*pixels.data[i]+.587*pixels.data[i+1]+.114*pixels.data[i+2]);pixels.data[i]=gray;histogram[gray]++;}
 let low=0,high=255;
 if(report){
  // Scout-report text is light gray on white; clip only a small number of outliers.
  const total=pixels.data.length/4,cutoff=total*.002;let count=0;
  for(let value=0;value<256;value++){count+=histogram[value];if(count>=cutoff){low=value;break;}}
  count=0;for(let value=255;value>=0;value--){count+=histogram[value];if(count>=cutoff){high=value;break;}}
  if(high-low<30){low=0;high=255;}
 }
 const gamma=report?2:2.6;
 for(let i=0;i<pixels.data.length;i+=4){const value=Math.round(255*Math.pow(Math.min(1,Math.max(0,(pixels.data[i]-low)/(high-low))),gamma));pixels.data[i]=pixels.data[i+1]=pixels.data[i+2]=value;pixels.data[i+3]=255;}
 ctx.putImageData(pixels,0,0);return canvas;
}
function encodedImage(initial,region){
 let canvas=initial,quality=.82,url=canvas.toDataURL('image/jpeg',quality);
 while(url.length>450000&&quality>.42){quality-=.1;url=canvas.toDataURL('image/jpeg',quality);}
 while(url.length>450000&&Math.max(canvas.width,canvas.height)>480){
  const smaller=document.createElement('canvas');smaller.width=Math.max(1,Math.round(canvas.width*.82));smaller.height=Math.max(1,Math.round(canvas.height*.82));smaller.getContext('2d').drawImage(canvas,0,0,smaller.width,smaller.height);canvas=smaller;url=canvas.toDataURL('image/jpeg',.65);
 }
 if(url.length>450000)return null;
 return {url,width:canvas.width,height:canvas.height,region};
}
export function prepareOcrImages(canvas,type){
 const full=encodedImage(adjustedCanvas(canvas),'full'),images=full?[full]:[];
 if(type==='match'&&canvas.width>canvas.height*1.6){
  const report=adjustedCanvas(canvas,{x:0,y:Math.round(canvas.height*.28),width:Math.round(canvas.width*.42),height:Math.round(canvas.height*.34),upscale:2,report:true});
  const encoded=encodedImage(report,'report');if(encoded)images.push(encoded);
 }
 return images;
}
